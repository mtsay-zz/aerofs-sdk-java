package com.aerofs.api.impl;

import com.aerofs.api.AeroFSAPI;

import javax.net.ssl.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AeroFSClient implements AeroFSAPI
{
    private final String        _apiEndpoint;
    private final SSLContext    _sslContext;
    private final String        _authToken;
    private final int           _connTimeout;
    private final int           _readTimeout;
    private final int           _chunkSize;
    private final int           _bufferSize;

    AeroFSClient(String apiEndpoint, SSLContext context, String authToken,
                 int connTimeout, int readTimeout, int chunkSize, int bufferSize)
    {
        _apiEndpoint    = apiEndpoint;
        _sslContext     = context;
        _authToken      = authToken;
        _connTimeout    = connTimeout;
        _readTimeout    = readTimeout;
        _chunkSize      = chunkSize;
        _bufferSize     = bufferSize;
    }

    private HttpURLConnection create(String route) throws IOException
    {
        URL url = new URL(String.format("%s/%s", _apiEndpoint, route));
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(_sslContext.getSocketFactory());
        conn.setRequestProperty("Authorization", String.format("Bearer %s", _authToken));
        conn.setConnectTimeout(_connTimeout);
        conn.setReadTimeout(_readTimeout);
        return conn;
    }

    private HttpURLConnection createChunked(String route) throws IOException
    {
        HttpURLConnection conn = create(route);
        conn.setChunkedStreamingMode(_chunkSize);
        return conn;
    }

    private Pattern rangePattern;
    private long getRangeEnd(String range)
            throws Exception
    {
        if (rangePattern == null) {
            rangePattern = Pattern.compile(join("\\p{Space}*",
                    "^", "bytes", "=", "\\p{Digit}+", "-", "(\\p{Digit}+)", "$"));
        }
        Matcher matcher = rangePattern.matcher(range);
        if (!matcher.matches()) {
            throw new Exception(String.format("Unexpected range pattern: %s.", range));
        }
        return Long.parseLong(matcher.group(1));
    }

    private static String join(String separator, String... values)
    {
        StringBuilder builder = new StringBuilder("");
        for (String value : values) {
            if (builder.length() != 0) builder.append(separator);
            builder.append(value);
        }
        return builder.toString();
    }

    private long copyStream(InputStream in, OutputStream out)
            throws IOException
    {
        long totalRead = 0;
        byte[] buffer = new byte[_bufferSize];
        int read;

        while ((read = in.read(buffer)) != -1) {
            totalRead += read;
            out.write(buffer, 0, read);
        }

        return totalRead;
    }

    private static InputStream limit(InputStream in, final long bytesToRead)
    {
        return new FilterInputStream(in) {
            long left = bytesToRead;
            long mark = -1;

            @Override
            public int available() throws IOException {
                return (int)Math.min(super.available(), left);
            }

            // this is necessary because we have to follow the contract of FilterInputStream
            @Override
            public synchronized void mark(int readLimit) {
                super.mark(readLimit);
                mark = left;
            }

            @Override
            public int read() throws IOException {
                if (left == 0) return -1;
                int result = super.read();
                if (left != -1) left--;
                return result;
            }

            @Override
            public int read(byte[] buf, int offset, int length) throws IOException {
                if (length == 0) return -1;
                length = (int)Math.min(length, left);
                int result = read(buf, offset, length);
                if (result != -1) left -= result;
                return result;
            }

            @Override
            public synchronized void reset() throws IOException {
                // this is necessary because the contract for mark/reset is too generous.
                if (!markSupported()) throw new IOException("Mark is not supported.");
                if (mark == -1) throw new IOException("Mark is not set.");
                super.reset();
                left = mark;
            }

            @Override
            public long skip(long n) throws IOException {
                n = Math.min(n, left);
                long skipped = super.skip(n);
                left -= skipped;
                return skipped;
            }
        };
    }

    @Override
    public String getFileContent(String fileID, OutputStream out) throws Exception
    {
        String route = String.format("files/%s/content", fileID);
        HttpURLConnection conn = createChunked(route);
        conn.setRequestMethod("GET");
        conn.connect();

        try {
            int code = conn.getResponseCode();
            if (code == 200) {
                String eTag = conn.getHeaderField("ETag");
                InputStream in = conn.getInputStream();
                try {
                    copyStream(in, out);
                } finally {
                    in.close();
                }
                return eTag;
            } else {
                throw new Exception(String.format("Unexpected returned code: %s", code));
            }
        } finally {
            conn.disconnect();
        }
    }

    @Override
    public String uploadFileContent(String fileID, String eTag, InputStream in) throws Exception
    {
        String route = String.format("files/%s/content", fileID);
        HttpURLConnection conn = createChunked(route);
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        conn.setRequestProperty("If-Match", eTag);

        OutputStream out = conn.getOutputStream();
        try {
            copyStream(in, out);
        } finally {
            out.close();
        }

        conn.connect();
        try {
            int code = conn.getResponseCode();
            if (code == 200) {
                return conn.getHeaderField("ETag");
            } else {
                throw new Exception(String.format("Unexpected return code: %s", code));
            }
        } finally {
            conn.disconnect();
        }
    }

    @Override
    public boolean isFileContentUpToDate(String fileID, String eTag) throws Exception
    {
        String route = String.format("files/%s/content", fileID);
        HttpURLConnection conn = create(route);
        conn.setRequestMethod("HEAD");
        conn.setDoInput(false);
        conn.setRequestProperty("If-None-Match", eTag);
        conn.connect();

        try {
            return conn.getResponseCode() == 316;
        } finally {
            conn.disconnect();
        }
    }

    @Override
    public String getFileContentETag(String fileID) throws Exception
    {
        String route = String.format("files/%s/content", fileID);
        HttpURLConnection conn = create(route);
        conn.setRequestMethod("HEAD");
        conn.setDoInput(false);
        conn.connect();

        try {
            int code = conn.getResponseCode();
            if (code == 200) {
                return conn.getHeaderField("ETag");
            } else {
                throw new Exception(String.format("Unexpected return code: %s", code));
            }
        } finally {
            conn.disconnect();
        }
    }

    @Override
    public long getFileContentRange(String fileID, String eTag, OutputStream out,
                                   long start, long end) throws Exception
    {
        String route = String.format("files/%s/content", fileID);
        HttpURLConnection conn = create(route);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("If-Match", eTag);
        conn.setRequestProperty("Content-Range", String.format("bytes %s-%s/*", start, end));
        conn.connect();

        try {
            int code = conn.getResponseCode();
            if (code == 200) {
                InputStream in = conn.getInputStream();
                try {
                    return copyStream(in, out);
                } finally {
                    in.close();
                }
            } else {
                throw new Exception(String.format("Unexpected return code: %s", code));
            }
        } finally {
            conn.disconnect();
        }
    }

    @Override
    public String startChunkedUpload(String fileID, String eTag) throws Exception
    {
        String route = String.format("files/%s/content", fileID);
        HttpURLConnection conn = create(route);
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("If-Match", eTag);
        conn.setRequestProperty("Content-Range", "bytes */*");
        conn.connect();

        try {
            int code = conn.getResponseCode();
            if (code == 200) {
                return conn.getHeaderField("Upload-ID");
            } else {
                throw new Exception(String.format("Unexpected return code: %s", code));
            }
        } finally {
            conn.disconnect();
        }
    }

    @Override
    public long doChunkedUpload(String fileID, String eTag, String uploadID, long start, long end,
                                InputStream in, long chunkSize)
            throws Exception
    {
        String route = String.format("files/%s/content", fileID);
        HttpURLConnection conn = create(route);
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);
        conn.setRequestProperty("If-Match", eTag);
        conn.setRequestProperty("Upload-ID", uploadID);
        conn.setRequestProperty("Content-Range", String.format("bytes %s-%s/*", start, end));
        conn.setRequestProperty("Content-Type", "application/octet-stream");

        OutputStream out = conn.getOutputStream();
        try {
            copyStream(limit(in, chunkSize), out);
        } finally {
            out.close();
        }

        conn.connect();
        try {
            int code = conn.getResponseCode();
            if (code == 200) {
                return getRangeEnd(conn.getHeaderField("Range"));
            } else {
                throw new Exception(String.format("Unexpected return code: %s", code));
            }
        } finally {
            conn.disconnect();
        }
    }

    @Override
    public long getChunkedUploadProgress(String fileID, String eTag, String uploadID)
            throws Exception
    {
        String route = String.format("files/%s/content", fileID);
        HttpURLConnection conn = create(route);
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("If-Match", eTag);
        conn.setRequestProperty("Upload-ID", uploadID);
        conn.setRequestProperty("Content-Range", "bytes */*");
        conn.connect();

        try {
            int code = conn.getResponseCode();
            if (code == 200) {
                return getRangeEnd(conn.getHeaderField("Range"));
            } else if (code == 400) {
                return 0;
            } else {
                throw new Exception(String.format("Unexpected return code: %s", code));
            }
        } finally {
            conn.disconnect();
        }
    }

    @Override
    public String finishChunkedUpload(String fileID, String eTag, String uploadID, long totalLength)
            throws Exception
    {
        String route = String.format("files/%s/content", fileID);
        HttpURLConnection conn = create(route);
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("If-Match", eTag);
        conn.setRequestProperty("Upload-ID", uploadID);
        conn.setRequestProperty("Content-Range", String.format("bytes */%s", totalLength));
        conn.connect();

        try {
            int code = conn.getResponseCode();
            if (code == 200) {
                return conn.getHeaderField("ETag");
            } else {
                throw new Exception(String.format("Unexpected return code: %s", code));
            }
        } finally {
            conn.disconnect();
        }
    }

    public static Builder newBuilder()
    {
        return new Builder();
    }

    public static class Builder
    {
        public static final String  DEFAULT_API_ENDPOINT    = "https://www.aerofs.com/api/v1.2";
        public static final int     DEFAULT_CONN_TIMEOUT    = 10000;
        public static final int     DEFAULT_READ_TIMEOUT    = 3000;
        public static final int     DEFAULT_CHUNK_SIZE      = 4096;
        public static final int     DEFAULT_BUFFER_SIZE     = 4096;

        public static final String  CERTIFICATE_TYPE        = "X.509";
        public static final String  CERTIFICATE_ALIAS       = "aerofs_api_endpoint";
        public static final String  KEYSTORE_TYPE           = "JKS";
        public static final String  TRUST_MANAGER_TYPE      = "SunX509";
        public static final String  SSL_CONTEXT_TYPE        = "TLSv1.2";

        private String      _apiEndPoint    = DEFAULT_API_ENDPOINT;
        private SSLContext  _sslContext;
        private String      _authToken;
        private int         _connTimeout    = DEFAULT_CONN_TIMEOUT;
        private int         _readTimeout    = DEFAULT_READ_TIMEOUT;
        private int         _chunkSize      = DEFAULT_CHUNK_SIZE;
        private int         _bufferSize     = DEFAULT_BUFFER_SIZE;

        public Builder setHost(String host)
        {
            return setAPIEndpoint(String.format("https://%s/api/v1.2", host));
        }

        public Builder setAPIEndpoint(String apiEndpoint)
        {
            _apiEndPoint = apiEndpoint;
            return this;
        }

        public Builder setSSLContext(SSLContext sslContext)
        {
            _sslContext = sslContext;
            return this;
        }

        public Builder loadSSLContextFromStream(InputStream stream)
        {
            try {
                CertificateFactory certificateFactory =
                        CertificateFactory.getInstance(CERTIFICATE_TYPE);
                Certificate certificate = certificateFactory.generateCertificate(stream);

                KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
                keyStore.load(null, "".toCharArray());
                keyStore.setCertificateEntry(CERTIFICATE_ALIAS, certificate);

                TrustManagerFactory trustManagerFactory =
                        TrustManagerFactory.getInstance(TRUST_MANAGER_TYPE);
                trustManagerFactory.init(keyStore);
                TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();

                SSLContext context = SSLContext.getInstance(SSL_CONTEXT_TYPE);
                context.init(null, trustManagers, null);
                _sslContext = context;
                return this;
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (GeneralSecurityException e) {
                throw new RuntimeException(e);
            }
        }

        public Builder loadTrustCertificateFromFile(String filename)
        {
            try {
                InputStream stream = new FileInputStream(filename);
                try {
                    return loadSSLContextFromStream(stream);
                } finally {
                    stream.close();
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public Builder loadTrustCertificateFromString(String certificateData)
        {
            try {
                InputStream stream = new ByteArrayInputStream(certificateData.getBytes());
                try {
                    return loadSSLContextFromStream(stream);
                } finally {
                    stream.close();
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        public Builder loadTrustCertificateFromProperty(Properties properties, String key)
        {
            return loadTrustCertificateFromString(properties.getProperty(key));
        }

        public Builder useInsecureCommunication()
        {
            try {
                SSLContext context = SSLContext.getInstance(SSL_CONTEXT_TYPE);
                TrustManager insecure = new X509TrustManager()
                {
                    @Override
                    public void checkClientTrusted(X509Certificate[] x509Certificates, String s)
                            throws CertificateException
                    {

                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] x509Certificates, String s)
                            throws CertificateException
                    {

                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers()
                    {
                        return null;
                    }
                };
                context.init(null, new TrustManager[] { insecure }, null);
                return setSSLContext(context);
            } catch (GeneralSecurityException e) {
                throw new RuntimeException(e);
            }
        }

        public Builder setAuthToken(String authToken)
        {
            _authToken = authToken;
            return this;
        }

        public Builder setConnectTimeout(int timeout)
        {
            _connTimeout = timeout;
            return this;
        }

        public Builder setReadTimeout(int timeout)
        {
            _readTimeout = timeout;
            return this;
        }

        public Builder setChunkSize(int chunkSize)
        {
            _chunkSize = chunkSize;
            return this;
        }

        public Builder setBufferSize(int bufferSize)
        {
            _bufferSize = bufferSize;
            return this;
        }

        public AeroFSAPI build()
        {
            return new AeroFSClient(_apiEndPoint, _sslContext, _authToken,
                    _connTimeout, _readTimeout, _chunkSize, _bufferSize);
        }
    }
}
