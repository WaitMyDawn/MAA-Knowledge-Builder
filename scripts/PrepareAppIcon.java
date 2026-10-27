import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.ArrayList;

/** jpackage 使用的多尺寸 ICO，像素内容始终来自应用的透明 PNG。 */
public class PrepareAppIcon {
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Usage: java PrepareAppIcon.java input.png output.ico");
        var source=ImageIO.read(Path.of(args[0]).toFile());
        if(source==null||source.getWidth()!=source.getHeight()||!source.getColorModel().hasAlpha())throw new IOException("Expected square PNG with alpha");
        int[] sizes={16,24,32,48,64,128,256};var images=new ArrayList<byte[]>();
        for(int size:sizes) {
            var image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();
            try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(source,0,0,size,size,null);}finally{g.dispose();}
            var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);images.add(out.toByteArray());
        }
        int offset=6+16*sizes.length,total=offset+images.stream().mapToInt(a->a.length).sum();
        var bytes=ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);bytes.putShort((short)0).putShort((short)1).putShort((short)sizes.length);
        for(int i=0;i<sizes.length;i++){bytes.put((byte)sizes[i]).put((byte)sizes[i]).put((byte)0).put((byte)0).putShort((short)1).putShort((short)32).putInt(images.get(i).length).putInt(offset);offset+=images.get(i).length;}
        images.forEach(bytes::put);Path target=Path.of(args[1]);Files.createDirectories(target.toAbsolutePath().getParent());Files.write(target,bytes.array());
    }
}
