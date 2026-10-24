package dev.einkflash;

/** Huawei JD79665AA panel variants; selection is physical, never inferred from D1. */
public enum PanelType {
    A0, A1;
    public static PanelType parse(String value) {
        if("A0".equals(value))return A0;
        if("A1".equals(value))return A1;
        throw new IllegalArgumentException("请先选择屏幕类型 A0 或 A1");
    }
    public byte[] pack(byte[] codes) {
        // 本机实测：A0 去交错后整体是原图的左右半互换，故在图像空间先把左右半互换抵消。
        if(this==A0) codes=halfSwapCodes(codes);
        return map(PanelProtocol.packCodes(codes,PanelProtocol.WIDTH,PanelProtocol.HEIGHT),false);
    }
    private static byte[] halfSwapCodes(byte[] codes) {
        int w=PanelProtocol.WIDTH,h=PanelProtocol.HEIGHT,half=w/2;byte[] out=new byte[codes.length];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)out[y*w+x]=codes[y*w+((x+half)%w)];
        return out;
    }
    private static int[] halfSwapPixels(int[] px) {
        int w=PanelProtocol.WIDTH,h=PanelProtocol.HEIGHT,half=w/2;int[] out=new int[px.length];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)out[y*w+x]=px[y*w+((x+half)%w)];
        return out;
    }
    private byte[] map(byte[] raw,boolean inverse) {
        if(raw.length!=PanelProtocol.IMAGE_BYTES)throw new IllegalArgumentException("屏幕数据长度错误");
        if(this==A1)return raw.clone();
        byte[] out=new byte[raw.length];
        // Transposed controller rows: first half -> even rows, second half -> reversed odd rows.
        final int rows=PanelProtocol.WIDTH,stride=PanelProtocol.HEIGHT/4;
        for(int row=0;row<rows;row++) {
            int target=row<rows/2?2*row:2*rows-1-2*row;
            System.arraycopy(raw,(inverse?target:row)*stride,out,(inverse?row:target)*stride,stride);
        }
        return out;
    }
    public int[] preview(byte[] packed) {
        int[] px=ImageRaster.preview(map(packed,true),PanelProtocol.WIDTH,PanelProtocol.HEIGHT);
        return this==A0?halfSwapPixels(px):px;
    }
    /** Stored scan 00 uses bottom-up pixels; scan 01 uses top-down pixels in each row. */
    public static byte[] forScan(byte[] packed,boolean topDown) {
        if(packed.length!=PanelProtocol.IMAGE_BYTES)throw new IllegalArgumentException("屏幕数据长度错误");
        if(!topDown)return packed.clone();
        int stride=PanelProtocol.HEIGHT/4;byte[] out=new byte[packed.length];
        for(int row=0;row<PanelProtocol.WIDTH;row++)for(int i=0;i<stride;i++) {
            int b=packed[row*stride+stride-1-i]&255;
            out[row*stride+i]=(byte)(((b&3)<<6)|((b&12)<<2)|((b&48)>>2)|((b&192)>>6));
        }
        return out;
    }
}
