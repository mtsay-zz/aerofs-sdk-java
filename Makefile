docs: src
	javadoc -sourcepath $< -d $@ com.aerofs.api

clean:
	-rm -rf docs out

.PHONY: clean
