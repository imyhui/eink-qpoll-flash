package dev.einkflash;

/** Average RGB before quantization, then fill each block with one palette color. */
public final class ImageBlocks {
    private ImageBlocks() { }
    public static byte[] quantize(int[] pixels,int width,int height,int block,int[] palette) {
        if(width<=0||height<=0||(long)width*height!=pixels.length||palette.length!=4)
            throw new IllegalArgumentException("Invalid image or palette");
        if(block!=1&&block!=2&&block!=4&&block!=8&&block!=16)
            throw new IllegalArgumentException("Invalid block size");
        byte[] codes=new byte[pixels.length];
        for(int y=0;y<height;y+=block)for(int x=0;x<width;x+=block) {
            int right=Math.min(width,x+block),bottom=Math.min(height,y+block),count=(right-x)*(bottom-y);
            int sr=0,sg=0,sb=0;
            for(int yy=y;yy<bottom;yy++)for(int xx=x;xx<right;xx++) {
                int p=pixels[yy*width+xx];sr+=(p>>>16)&255;sg+=(p>>>8)&255;sb+=p&255;
            }
            float r=sr/(float)count,g=sg/(float)count,b=sb/(float)count;
            int best=0;float score=Float.MAX_VALUE;
            for(int k=0;k<4;k++) {
                float dr=r-((palette[k]>>>16)&255),dg=g-((palette[k]>>>8)&255),db=b-(palette[k]&255);
                float distance=dr*dr+dg*dg+db*db;
                if(distance<score){score=distance;best=k;}
            }
            for(int yy=y;yy<bottom;yy++)java.util.Arrays.fill(codes,yy*width+x,yy*width+right,(byte)best);
        }
        return codes;
    }
}
