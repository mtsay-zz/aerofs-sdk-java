package com.aerofs.api;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * @author <a href="mailto:alex@aerofs.com">Alex Tsay</a>
 *
 * This is the AeroFS API Java client library.
 *
 * This library is provided to wrap the underlying network calls necessary for each method; calling
 * any method specified should be equivalent to making one HTTP request to the API endpoint.
 *
 * All method calls are synchronous and blocks the caller until the underlying network calls
 * complete.
 *
 * This library observes a strict-consistency model. That means
 *  - the library will request the API endpoint to serve all requests on the same node. Should
 *    that node become unavailable, all subsequent requests will fail until the said node becomes
 *    available again or a new instance of the client is created.
 *  - all updates to a resource requires the ETag of the resource. Should the provided ETag not
 *    match the ETag of the resource on the endpoint, it means that the resource has been updated
 *    from another source and the update request will be rejected. In which case, the caller should
 *    fetch the latest ETag, and possibly content, before making any further request on the said
 *    resource.
 */
public interface AeroFSAPI
{
    /**
     * Download the entire file content of a file in one go.
     *
     * @param fileID            The file identifier for the file to download.
     * @param stream            The content of the file will be written to this stream.
     * @return                  The ETag associated with the file content.
     * @throws AeroFSException  If the operation failed.
     */
    String getFileContent(String fileID, OutputStream stream)
            throws AeroFSException;

    /**
     * Upload the entire file content of a file in one go.
     *
     * @param fileID            The file identifier of the file to overwrite.
     * @param eTag              The ETag of the file to overwrite.
     * @param stream            The new content for the file to overwrite.
     * @return                  The ETag associated with the new file content.
     * @throws AeroFSException  If the operation failed.
     */
    String uploadFileContent(String fileID, String eTag, InputStream stream)
            throws AeroFSException;

    /**
     * Checks whether a particular ETag for a file matches the ETag of the same file on the
     * endpoint.
     *
     * @param fileID            The file identifier of the file of interest.
     * @param eTag              The ETag of the file of interest.
     * @return {@code True}     if and only if the provided ETag matches the ETag on
     *                          the endpoint for the file of interest.
     * @throws AeroFSException  If the operation failed.
     */
    boolean isFileContentUpToDate(String fileID, String eTag)
            throws AeroFSException;

    /**
     * Retrieve the ETag of a file on the endpoint without downloading the content. This can be
     * used to download a file in chunks.
     *
     * @param fileID            The file identifier of the file of interest.
     * @return                  The ETag of the file of interest.
     * @throws AeroFSException  If the operation failed.
     */
    String getFileContentETag(String fileID)
            throws AeroFSException;

    /**
     * Download partial content of a file of interest. The range should be inclusive on both end.
     *
     * @param fileID            The file identifier of the file to download.
     * @param eTag              The ETag of the file to download.
     * @param stream            The downloaded content of the file will be written to this stream.
     * @param start             The start of the range to download.
     * @param end               The end of the range to download.
     * @return                  The number of bytes downloaded.
     * @throws AeroFSException  If the operation failed.
     */
    int getFileContentRange(String fileID, String eTag, OutputStream stream,
                            int start, int end)
            throws AeroFSException;

    /**
     * Initial a chunked upload.
     *
     * @param fileID            The file identifier of the file to overwrite.
     * @param eTag              The ETag of the file to overwrite.
     * @return                  The Upload-ID of the chunked upload.
     * @throws AeroFSException  If the operation failed.
     */
    String startChunkedUpload(String fileID, String eTag)
            throws AeroFSException;

    /**
     * Upload a particular chunk of a file using chunked upload.
     *
     * @param fileID            The file identifier of the file to overwrite.
     * @param eTag              The ETag of the file to overwrite.
     * @param uploadID          The Upload-ID to identify the chunked upload.
     * @param data              A buffer storing the data to be uploaded.
     * @param offset            An offset into the buffer to start uploading the content.
     * @param length            The length of the content in the buffer to upload.
     * @return                  The number of bytes successfully uploaded so far.
     * @throws AeroFSException  If the operation failed.
     */
    long doChunkedUpload(String fileID, String eTag, String uploadID,
                         byte[] data, int offset, int length)
            throws AeroFSException;

    /**
     * Retrieve the progress of a chunked upload.
     *
     * @param fileID            The file identifier of the file to overwrite.
     * @param eTag              The ETag of the file to overwrite.
     * @param uploadID          The Upload-ID to identify the chunked upload.
     * @return                  The number of bytes successfully uploaded so far.
     * @throws AeroFSException  If the operation failed.
     */
    long getChunkedUploadProgress(String fileID, String eTag, String uploadID)
            throws AeroFSException;

    /**
     * Finishes a chunked upload and commit the content change.
     *
     * @param fileID            The file identifier of the file to overwrite.
     * @param eTag              The ETag of the file to overwrite.
     * @param uploadID          The Upload-ID to identify the chunked upload request.
     * @param totalLength       The total length of the content.
     * @return                  The ETag of the new file content.
     * @throws AeroFSException  If the operation failed.
     */
    String finishChunkedUpload(String fileID, String eTag, String uploadID, int totalLength)
            throws AeroFSException;
}
