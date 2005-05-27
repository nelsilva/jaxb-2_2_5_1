package com.sun.xml.bind.v2;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;

import com.sun.xml.bind.v2.runtime.unmarshaller.Base64Data;

/**
 * {@link ByteArrayOutputStream} with access to its raw buffer.
 */
public final class ByteArrayOutputStreamEx extends ByteArrayOutputStream {
    public ByteArrayOutputStreamEx() {
    }

    public ByteArrayOutputStreamEx(int size) {
        super(size);
    }

    public void set(Base64Data dt) {
        dt.set(buf,count);
    }

    public byte[] getBuffer() {
        return buf;
    }

    /**
     * Reads the given {@link InputStream} completely into the buffer.
     */
    public void readFrom(InputStream is) throws IOException {
        while(true) {
            if(count==buf.length) {
                // realllocate
                byte[] data = new byte[buf.length*2];
                System.arraycopy(buf,0,data,0,buf.length);
                buf = data;
            }

            int sz = is.read(buf,count,buf.length-count);
            count += sz;
            if(count<0)     return;
        }
    }
}
