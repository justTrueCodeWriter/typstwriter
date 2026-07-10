package com.typstwriter;

public class CompileResult {
    public byte[] data;
    public int len;
    public int format;

    public CompileResult(byte[] data, int len, int format) {
        this.data = data;
        this.len = len;
        this.format = format;
    }
}
