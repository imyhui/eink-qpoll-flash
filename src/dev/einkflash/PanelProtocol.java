package dev.einkflash;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Protocol and factory scan packing adapted from kur0neko/huawei-epd-pn532.
 * Required Notice: Copyright (c) 2026 kur0neko
 * PolyForm Noncommercial 1.0.0; see bundled licenses/kur0neko-LICENSE.txt. */
public final class PanelProtocol {
    public static final int WIDTH=552, HEIGHT=768, IMAGE_BYTES=105984;
    public static final int DEFAULT_POLL_MS=500;
    public static final byte[] D1=hex("00D1000000"), D8=hex("F0D8000005000000000E"), DE=hex("F0DE000001");
    private static final byte[] PIN=hex("00200001055265699919");
    private static final byte[] GEOMETRY=hex("F0C72006000228"), SCAN=hex("00120030FFFFFF");
    public interface Transport { byte[] exchange(byte[] command,int timeoutMs) throws Exception; }
    public interface Control {
        long now();
        void sleep(long ms) throws Exception;
        void check() throws Exception;
        void event(String kind,String message);
    }
    public static final class Info {
        public final Map<Integer,byte[]> fields;
        public final String uid;
        public boolean topDown(){return fields.get(0xA1)[0]==1;}
        Info(Map<Integer,byte[]> fields){this.fields=fields;byte[] id=fields.get(0xC1);this.uid=id==null?"未提供":toHex(id);}
        public String description(){return "UID="+uid+" · 552×768 四色 · 保留当前原厂配置";}
    }
    public static byte[] hex(String s) {
        if((s.length()&1)!=0)throw new IllegalArgumentException("Odd hex length");
        byte[] out=new byte[s.length()/2];
        for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);
        return out;
    }
    public static String toHex(byte[] data){StringBuilder s=new StringBuilder();for(byte b:data)s.append(String.format("%02X",b&255));return s.toString();}
    public static int status(byte[] response) {
        if(response==null||response.length<2)throw new IllegalArgumentException("回包没有状态字");
        return ((response[response.length-2]&255)<<8)|(response[response.length-1]&255);
    }
    private static String meaning(int sw) {
        switch(sw) {
            case 0x6502:case 0x68CA:case 0x698A:return "低电压或供能不足";
            case 0x68CB:return "屏幕刷新超时";
            case 0x6992:return "数据解压失败";
            case 0x6993:return "解压后的数据长度错误";
            case 0x6995:return "图像分包序号错误";
            case 0x6982:return "安全状态不满足";
            case 0x6985:return "访问条件不满足";
            case 0x6986:case 0x68C6:return "屏幕复位失败或屏幕不存在";
            default:return "设备返回错误";
        }
    }
    public static byte[] requireOk(byte[] response,String label) {
        int sw=status(response);
        if(sw!=0x9000)throw new IllegalArgumentException(label+"：SW="+String.format("%04X",sw)+"（"+meaning(sw)+"），已停止，不重发");
        return Arrays.copyOf(response,response.length-2);
    }
    public static Info parseInfo(byte[] response) {
        byte[] data=requireOk(response,"D1 身份读取");
        Map<Integer,byte[]> fields=new LinkedHashMap<>();
        for(int i=0;i<data.length;) {
            if(i+2>data.length)throw new IllegalArgumentException("D1 TLV 头截断");
            int tag=data[i++]&255,n=data[i++]&255;
            if(fields.containsKey(tag)||i+n>data.length)throw new IllegalArgumentException("D1 TLV 重复或截断");
            fields.put(tag,Arrays.copyOfRange(data,i,i+n));i+=n;
        }
        StringBuilder mismatch=new StringBuilder();
        // C0 是单元相关标识（不同批次/固件值不同，非协议关键）；本机实测与 reinkstone 已可刷，故仅作诊断、不卡关。
        for(int[] f:new int[][]{{0xA0,7},{0xA1,7},{0xB1,1},{0xB2,1},{0xD1,7}}) {
            byte[] actual=fields.get(f[0]);
            if(actual==null||actual.length!=f[1])
                mismatch.append(String.format("\n%02X 长度：期望=%d，实际=%s",f[0],f[1],actual==null?"缺失":actual.length+" ("+toHex(actual)+")"));
        }
        int[] tags={0xA0,0xA1,0xB1,0xD1};
        byte[][] expected={GEOMETRY,SCAN,hex("02"),hex("01")};
        for(int i=0;i<tags.length;i++) {
            byte[] actual=fields.get(tags[i]);
            if(actual==null||actual.length==0)continue;
            boolean matches=tags[i]==0xD1?actual[0]==1:Arrays.equals(actual,expected[i]);
            if(tags[i]==0xA1&&Arrays.equals(actual,hex("01120030FFFFFF")))matches=true;
            if(!matches)mismatch.append(String.format("\n%02X%s：期望=%s，实际=%s",tags[i],tags[i]==0xD1?" 首字节":"",toHex(expected[i]),toHex(actual)));
        }
        if(mismatch.length()>0)
            throw new IllegalArgumentException("目标配置与已验证的原厂扫描配置不匹配；不会改写 DB/DA"+mismatch);
        // C0/UID 仅作诊断；几何(A0)与扫描(A1)一致即视为兼容屏，无 ID 白名单。
        return new Info(fields);
    }
    /** Four vertical pixels per byte, right-to-left, bottom-to-top, MSB first. */
    public static byte[] packCodes(byte[] codes,int width,int height) {
        if(width<=0||height<=0||height%4!=0||codes.length!=width*height)throw new IllegalArgumentException("像素尺寸错误");
        byte[] packed=new byte[width*height/4];int out=0;
        for(int x=width-1;x>=0;x--)for(int y=height-1;y>=0;y-=4) {
            int value=0;
            for(int n=0;n<4;n++){int code=codes[(y-n)*width+x]&255;if(code>3)throw new IllegalArgumentException("颜色码错误");value|=code<<(6-2*n);}
            packed[out++]=(byte)value;
        }
        return packed;
    }
    /** Valid LZO1X literal-only stream. No native library and no padding of the final block. */
    public static byte[] literalLzo(byte[] raw) {
        if(raw.length<1||raw.length>2000)throw new IllegalArgumentException("LZO 块尺寸错误");
        ByteArrayOutputStream stream=new ByteArrayOutputStream(raw.length+16);
        if(raw.length<=238)stream.write(17+raw.length);
        else {stream.write(0);int rest=raw.length-18;while(rest>255){stream.write(0);rest-=255;}stream.write(rest);}
        stream.write(raw,0,raw.length);stream.write(0x11);stream.write(0);stream.write(0);return stream.toByteArray();
    }
    /**
     * 真压缩 LZO1X 编码器（贪心 + 哈希链匹配），替代仅打包不压缩的 literalLzo。
     * 大片纯白/纯色背景大幅缩减分包数（可由 476 包降至 100~200 包），大幅提升传输速度降低掉电风险。
     * 无法压缩时自动回退为 literalLzo。
     */
    public static byte[] compressedLzo(byte[] raw) {
        if(raw.length<1||raw.length>2000)throw new IllegalArgumentException("LZO 块尺寸错误");
        LzoWriter writer=new LzoWriter(raw.length+raw.length/8+80);
        int[] head=new int[1<<14],prev=new int[raw.length];
        java.util.Arrays.fill(head,-1);java.util.Arrays.fill(prev,-1);
        int literalStart=0,pos=0,lastMatch=-1;
        while(pos+2<raw.length) {
            int bestPos=-1,bestLength=0;
            if(pos>0) {
                int key=hash(raw,pos),candidate=head[key],visited=0;
                while(candidate>=0&&pos-candidate<=0x4000&&visited++<64) {
                    int max=raw.length-pos,length=0;
                    while(length<max&&raw[candidate+length]==raw[pos+length])length++;
                    if(length>bestLength||(length==bestLength&&length>=3&&pos-candidate<pos-bestPos)) {
                        bestLength=length;bestPos=candidate;
                    }
                    if(bestLength==max)break;
                    candidate=prev[candidate];
                }
            }
            int current=pos;
            int key=hash(raw,current);
            prev[current]=head[key];head[key]=current;
            if(bestLength>=3) {
                writer.literal(raw,literalStart,current-literalStart,lastMatch);
                int offset=current-bestPos;
                lastMatch=writer.match(bestLength,offset);
                for(int insert=current+1;insert<current+bestLength&&insert+2<raw.length;insert++) {
                    key=hash(raw,insert);
                    prev[insert]=head[key];head[key]=insert;
                }
                pos=current+bestLength;
                literalStart=pos;
            } else {
                pos++;
            }
        }
        writer.literal(raw,literalStart,raw.length-literalStart,lastMatch);
        writer.write(0x11);writer.write(0);writer.write(0);
        byte[] literal=literalLzo(raw);
        return writer.size<literal.length?writer.toByteArray():literal;
    }
    private static int hash(byte[] data,int pos) {
        int value=((data[pos]&255)<<16)|((data[pos+1]&255)<<8)|(data[pos+2]&255);
        return ((value*0x9E3779B1)>>>18)&((1<<14)-1);
    }
    private static final class LzoWriter {
        private final byte[] buffer;
        private int size;
        LzoWriter(int capacity){buffer=new byte[capacity];}
        void write(int value){buffer[size++]=(byte)value;}
        void append(byte[] raw,int offset,int length){
            System.arraycopy(raw,offset,buffer,size,length);size+=length;
        }
        void literal(byte[] raw,int offset,int length,int lastMatch) {
            if(length<=0)return;
            if(length<=3&&lastMatch>=0) {
                buffer[lastMatch]|=(byte)length;
            } else if(size==0&&length<=238) {
                write(17+length);
            } else if(length<=18) {
                write(length-3);
            } else {
                write(0);zeroLength(length-18);
            }
            append(raw,offset,length);
        }
        int match(int length,int offset) {
            if(offset<1)throw new IllegalArgumentException("LZO 偏移错误");
            if(length<=8&&offset<=0x0800) {
                offset--;
                write(((length-1)<<5)|((offset&7)<<2));
                write(offset>>3);
            } else {
                offset--;
                if(length<=33)write(0x20|(length-2));
                else {
                    write(0x20);zeroLength(length-33);
                }
                write(offset<<2);write(offset>>6);
            }
            return size-2;
        }
        private void zeroLength(int length) {
            while(length>255){write(0);length-=255;}
            write(length);
        }
        byte[] toByteArray(){return Arrays.copyOf(buffer,size);}
    }
    public static byte[] d3(byte[] compressed,int block,int sub) {
        if(block<0||block>255||sub<0||sub>255||sub*250>=compressed.length)throw new IllegalArgumentException("D3 编号错误");
        int offset=sub*250,count=Math.min(250,compressed.length-offset);
        byte[] packet=new byte[count+7];
        packet[0]=(byte)0xF0;packet[1]=(byte)0xD3;packet[2]=0;
        packet[3]=(byte)(offset+count==compressed.length?1:0);packet[4]=(byte)(count+2);
        packet[5]=(byte)block;packet[6]=(byte)sub;System.arraycopy(compressed,offset,packet,7,count);return packet;
    }
    public static byte[] d4(int powerMs) {
        if(powerMs<100||powerMs>12700||powerMs%100!=0)throw new IllegalArgumentException("上电等待参数错误");
        return new byte[]{(byte)0xF0,(byte)0xD4,(byte)(0x80|powerMs/100),(byte)0x80,0};
    }
    private final Transport transport;
    private final Control control;
    public PanelProtocol(Transport transport,Control control){this.transport=transport;this.control=control;}
    private byte[] exchange(byte[] cmd,String label,int timeout,boolean checkStatus) throws Exception {
        control.check();
        byte[] response=transport.exchange(cmd,timeout);
        int sw=status(response);
        // Never emit the authentication payload or image content into logs.
        if(cmd[1]!=(byte)0xD3&&cmd[1]!=(byte)0xDE)control.event("APDU",label+"：SW="+String.format("%04X",sw));
        if(checkStatus)requireOk(response,label);
        return response;
    }
    private void pause(long ms) throws Exception {
        long until=control.now()+ms;
        while(control.now()<until){control.check();control.sleep(Math.min(100,until-control.now()));}
        control.check();
    }
    public Info identify() throws Exception {
        control.event("STAGE","checking|读取并校验屏幕规格");pause(1000);
        Info info=parseInfo(exchange(D1,"D1",5000,true));
        control.event("IDENTITY",info.description());return info;
    }
    public boolean write(byte[] packed,int powerMs,int pollMs) throws Exception {
        if(packed.length!=IMAGE_BYTES)throw new IllegalArgumentException("图像必须为 105984 字节");
        if(pollMs<0||pollMs>500)throw new IllegalArgumentException("忙状态查询间隔必须为 0–500 ms");
        byte[] refresh=d4(powerMs); // Validate every option before any authentication/write.
        control.event("TIMING","忙状态查询间隔="+pollMs+" ms（每次应答后等待）");
        Info initial=identify();
        packed=PanelType.forScan(packed,initial.topDown());
        control.event("SCAN","采用板内扫描方向="+(initial.topDown()?"01（从上到下）":"00（从下到上）")+"；不改写驱动配置");
        control.event("WIRE_IMAGE","实际传输 SHA-256="+toHex(java.security.MessageDigest.getInstance("SHA-256").digest(packed)));
        control.event("STAGE","authenticating|检查图像区访问权限");
        byte[] access=exchange(D8,"D8 权限查询",5000,false);
        if(status(access)==0x6985) {
            exchange(PIN,"验证已公开的默认 PIN（仅一次）",5000,true);
            exchange(D8,"D8 再次检查",5000,true);
        } else requireOk(access,"D8 权限查询");
        // Check the same factory profile again; no DB/DA configuration writes exist here.
        Info rechecked=parseInfo(exchange(D1,"D1 写入前复核",5000,true));
        if(initial.topDown()!=rechecked.topDown())throw new IllegalArgumentException("扫描配置在会话中发生变化，未传图");
        control.event("STAGE","uploading|正在传输图片，请保持贴合");
        int confirmed=0,total=0;
        byte[][] blocks=new byte[(packed.length+1999)/2000][];
        for(int block=0,offset=0;offset<packed.length;block++,offset+=2000) {
            blocks[block]=compressedLzo(Arrays.copyOfRange(packed,offset,Math.min(offset+2000,packed.length)));
            total+=(blocks[block].length+249)/250;
        }
        control.event("TIMING","传输负载 "+total+" 包（字面量方案恒为 476 包）");
        for(int block=0;block<blocks.length;block++) {
            byte[] compressed=blocks[block];
            for(int sub=0;sub*250<compressed.length;sub++) {
                exchange(d3(compressed,block,sub),"D3 块 "+block+" / 包 "+sub,5000,true);
                confirmed++;
                if(confirmed%8==0||confirmed==total)control.event("PROGRESS",confirmed+"|"+total+"|图片传输");
            }
        }
        control.event("STAGE","refreshing|图片已传完，正在启动屏幕刷新");
        control.check();
        requireOk(exchange(refresh,"D4 刷新（上电等待 "+powerMs+" ms）",powerMs+5000,true),"D4");
        long started=control.now(),deadline=started+90000;
        boolean busySeen=false;int idle=0;Boolean previous=null;
        control.event("STAGE","polling|保持贴合供能，等待屏幕刷新");
        while(control.now()<deadline) {
            byte[] data=requireOk(exchange(DE,"DE 状态查询",5000,true),"DE");
            if(data.length!=1||(data[0]!=0&&data[0]!=1))throw new IllegalArgumentException("DE 没有有效忙状态字节");
            boolean busy=data[0]==1;busySeen|=busy;idle=busy?0:idle+1;
            if(previous==null||previous!=busy){control.event("BUSY",busy?"屏幕报告忙，继续供能":"屏幕报告空闲");previous=busy;}
            if(!busy&&idle>=3&&(busySeen||control.now()-started>=20000)) {
                control.event(busySeen?"REFRESH_DONE":"REFRESH_UNCONFIRMED",busySeen
                        ?"已观察到忙→连续三次空闲，请目视确认画面是否完整"
                        :"连续空闲但未观察到忙，不能确认刷新，请检查实际画面");
                return busySeen;
            }
            pause(pollMs);
        }
        throw new IllegalStateException("刷新状态等待超过 90 秒；未自动重发 D4");
    }
}
