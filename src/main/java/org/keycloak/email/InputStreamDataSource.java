package org.keycloak.email;

import javax.activation.DataSource;
import javax.activation.MimetypesFileTypeMap;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;


public class InputStreamDataSource implements DataSource {
    private Boolean fistRead;
    private final String name;
    private final InputStream inputStream1;
    private final InputStream inputStream2;

    public InputStreamDataSource(InputStream inputStream1, InputStream inputStream2, String name) {
	this.name = name;
        this.fistRead = true;
        this.inputStream1 = inputStream1;
        this.inputStream2 = inputStream2;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        if (this.fistRead) {
           this.fistRead = false;
           return this.inputStream1;
        } 

        this.inputStream1.close();
        return this.inputStream2;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public String getContentType() {
        return new MimetypesFileTypeMap().getContentType(name);
    }

    @Override
    public String getName() {
        return name;
    }
}
