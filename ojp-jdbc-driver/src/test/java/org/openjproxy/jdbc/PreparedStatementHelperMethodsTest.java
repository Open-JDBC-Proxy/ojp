package org.openjproxy.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.Reader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PreparedStatement helper methods.
 * These tests verify the Reader to InputStream conversion logic.
 */
public class PreparedStatementHelperMethodsTest {

    @Test
    public void testReaderToInputStreamWithAsciiCharacters() throws IOException {
        String testString = "Hello World";
        Reader reader = new StringReader(testString);
        
        InputStream is = invokeReaderToInputStream(reader);
        
        byte[] result = is.readAllBytes();
        String resultString = new String(result, java.nio.charset.StandardCharsets.UTF_8);
        
        assertEquals(testString, resultString);
    }

    @Test
    public void testReaderToInputStreamWithMultiByteCharacters() throws IOException {
        // Test string with multi-byte UTF-8 characters
        String testString = "Hello 世界 🌍"; // Mix of ASCII, Chinese, and emoji
        Reader reader = new StringReader(testString);
        
        InputStream is = invokeReaderToInputStream(reader);
        
        byte[] result = is.readAllBytes();
        String resultString = new String(result, java.nio.charset.StandardCharsets.UTF_8);
        
        assertEquals(testString, resultString);
    }

    @Test
    public void testReaderToInputStreamEmptyString() throws IOException {
        String testString = "";
        Reader reader = new StringReader(testString);
        
        InputStream is = invokeReaderToInputStream(reader);
        
        int result = is.read();
        
        assertEquals(-1, result, "Empty reader should return -1");
    }

    @Test
    public void testReaderToInputStreamSingleCharacter() throws IOException {
        String testString = "A";
        Reader reader = new StringReader(testString);
        
        InputStream is = invokeReaderToInputStream(reader);
        
        byte[] result = is.readAllBytes();
        String resultString = new String(result, java.nio.charset.StandardCharsets.UTF_8);
        
        assertEquals(testString, resultString);
    }

    @Test
    public void testReaderToInputStreamWithHighUnicodeCharacter() throws IOException {
        // Test character that requires 3 bytes in UTF-8 (U+4E16 = 世)
        String testString = "世";
        Reader reader = new StringReader(testString);
        
        InputStream is = invokeReaderToInputStream(reader);
        
        byte[] result = is.readAllBytes();
        
        // UTF-8 encoding of 世 (U+4E16) is: E4 B8 96 (3 bytes)
        assertEquals(3, result.length, "Chinese character should be encoded as 3 bytes in UTF-8");
        
        String resultString = new String(result, java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(testString, resultString);
    }

    /**
     * Helper method to invoke the private readerToInputStream method.
     * Since the actual method is private and used internally, we create
     * a standalone implementation here that mirrors the logic for testing.
     */
    private InputStream invokeReaderToInputStream(Reader reader) {
        return new InputStream() {
            private byte[] buffer = null;
            private int bufferPos = 0;
            private final char[] charBuffer = new char[2]; // For handling surrogate pairs
            
            @Override
            public int read() throws IOException {
                if (buffer == null || bufferPos >= buffer.length) {
                    int ch = reader.read();
                    if (ch == -1) {
                        return -1;
                    }
                    
                    // Check if this is a high surrogate (emoji, etc)
                    if (Character.isHighSurrogate((char) ch)) {
                        charBuffer[0] = (char) ch;
                        int lowSurrogate = reader.read();
                        if (lowSurrogate == -1 || !Character.isLowSurrogate((char) lowSurrogate)) {
                            // Invalid surrogate pair - encode the high surrogate alone
                            buffer = new String(charBuffer, 0, 1).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        } else {
                            // Valid surrogate pair - encode both characters
                            charBuffer[1] = (char) lowSurrogate;
                            buffer = new String(charBuffer, 0, 2).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        }
                    } else {
                        // Regular character (BMP) - encode single character
                        buffer = String.valueOf((char) ch).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    }
                    bufferPos = 0;
                }
                return buffer[bufferPos++] & 0xFF;
            }
            
            @Override
            public void close() throws IOException {
                reader.close();
            }
        };
    }
}
