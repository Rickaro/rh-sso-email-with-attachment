package org.keycloak.email;

import javax.activation.DataSource;
import javax.activation.MimetypesFileTypeMap;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;


public class InputStreamDataSource implements DataSource {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final String name;

    public InputStreamDataSource(InputStream inputStream, String name) {
	this.name = name;
        try {
            int nRead;
            byte[] data = new byte[16384];
            while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }

            buffer.flush();
            inputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new ByteArrayInputStream(buffer.toByteArray());
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
