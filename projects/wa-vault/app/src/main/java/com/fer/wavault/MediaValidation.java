package com.fer.wavault;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.Locale;

/** Lightweight structural validation for staged photos/videos. */
public final class MediaValidation {
    private MediaValidation() {}

    public static final String FULL="FULL";
    public static final String PARTIAL="PARTIAL";
    public static final String INVALID="INVALID";

    public static final class Result {
        public final String state;
        public final String reason;
        public final int width;
        public final int height;
        public final long durationMs;
        public Result(String state,String reason,int width,int height,long durationMs){
            this.state=state;this.reason=reason;this.width=width;this.height=height;this.durationMs=durationMs;
        }
        public boolean full(){return FULL.equals(state);}
        public boolean partial(){return PARTIAL.equals(state);}
    }

    public static Result validate(File file,String type){
        if(file==null||!file.exists()||!file.isFile())return new Result(INVALID,"FILE_MISSING",0,0,0L);
        long bytes=file.length();
        if(bytes<=0)return new Result(INVALID,"ZERO_BYTES",0,0,0L);
        if("image".equals(type))return validateImage(file,bytes);
        if("video".equals(type))return validateVideo(file,bytes);
        return new Result(INVALID,"UNSUPPORTED_TYPE",0,0,0L);
    }

    private static Result validateImage(File file,long bytes){
        try{
            BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(file.getAbsolutePath(),o);
            if(o.outWidth>0&&o.outHeight>0){
                if(imageContainerComplete(file))return new Result(FULL,"IMAGE_DECODE_OK",o.outWidth,o.outHeight,0L);
                return new Result(PARTIAL,"IMAGE_TRUNCATED",o.outWidth,o.outHeight,0L);
            }
        }catch(Throwable ignored){}
        return new Result(bytes>=1024?PARTIAL:INVALID,bytes>=1024?"IMAGE_TRUNCATED":"IMAGE_TOO_SMALL",0,0,0L);
    }

    private static Result validateVideo(File file,long bytes){
        MediaMetadataRetriever r=new MediaMetadataRetriever();
        try{
            r.setDataSource(file.getAbsolutePath());
            long duration=parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            int width=(int)Math.max(0L,parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)));
            int height=(int)Math.max(0L,parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)));
            boolean hasVideo=width>0&&height>0;
            boolean durationOk=duration>0L;
            boolean containerOk=videoContainerComplete(file);
            if(hasVideo&&durationOk&&containerOk)return new Result(FULL,"VIDEO_PLAYABLE",width,height,duration);
            if(bytes>=4096L)return new Result(PARTIAL,!hasMoovAtom(file)?"VIDEO_TRUNCATED_NO_MOOV":(!containerOk?"VIDEO_TRUNCATED_CONTAINER":"VIDEO_METADATA_INCOMPLETE"),width,height,duration);
            return new Result(INVALID,"VIDEO_TOO_SMALL",width,height,duration);
        }catch(Throwable t){
            return new Result(bytes>=4096L?PARTIAL:INVALID,bytes>=4096L?"VIDEO_TRUNCATED":"VIDEO_INVALID",0,0,0L);
        }finally{try{r.release();}catch(Throwable ignored){}}
    }

    public static File extractVideoPreview(File video,File out){
        if(video==null||out==null||!video.exists())return null;
        MediaMetadataRetriever r=new MediaMetadataRetriever();
        try{
            r.setDataSource(video.getAbsolutePath());
            Bitmap b=r.getFrameAtTime(0L,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if(b==null)b=r.getFrameAtTime();
            if(b==null)return null;
            int max=1280;
            Bitmap use=b;
            if(b.getWidth()>max||b.getHeight()>max){
                float s=Math.min(max/(float)b.getWidth(),max/(float)b.getHeight());
                use=Bitmap.createScaledBitmap(b,Math.max(1,Math.round(b.getWidth()*s)),Math.max(1,Math.round(b.getHeight()*s)),true);
            }
            try(FileOutputStream fos=new FileOutputStream(out)){
                if(!use.compress(Bitmap.CompressFormat.JPEG,88,fos))return null;
                fos.flush();
                fos.getFD().sync();
            }
            if(use!=b)use.recycle();b.recycle();
            return out.exists()&&out.length()>1000L?out:null;
        }catch(Throwable ignored){return null;}
        finally{try{r.release();}catch(Throwable ignored){}}
    }

    private static long parseLong(String s){try{return s==null?0L:Long.parseLong(s);}catch(Throwable t){return 0L;}}

    private static boolean imageContainerComplete(File f){
        String n=f.getName()==null?"":f.getName().toLowerCase(Locale.ROOT);long len=f.length();
        try(RandomAccessFile r=new RandomAccessFile(f,"r")){
            if((n.endsWith(".jpg")||n.endsWith(".jpeg"))&&len>=2){r.seek(len-2);return r.readUnsignedByte()==0xff&&r.readUnsignedByte()==0xd9;}
            if(n.endsWith(".png")&&len>=12){r.seek(Math.max(0,len-32));byte[] b=new byte[(int)Math.min(32,len)];r.readFully(b);for(int i=0;i+3<b.length;i++)if(b[i]=='I'&&b[i+1]=='E'&&b[i+2]=='N'&&b[i+3]=='D')return true;return false;}
            if(n.endsWith(".webp")&&len>=12){r.seek(0);byte[] h=new byte[12];r.readFully(h);if(h[0]=='R'&&h[1]=='I'&&h[2]=='F'&&h[3]=='F'){long sz=((h[4]&255L)|((h[5]&255L)<<8)|((h[6]&255L)<<16)|((h[7]&255L)<<24))+8L;return sz<=len;}}
        }catch(Throwable ignored){}
        // HEIC/GIF and unknown image containers rely on decoder success.
        return true;
    }

    private static boolean videoContainerComplete(File f){
        String n=f.getName()==null?"":f.getName().toLowerCase(Locale.ROOT);
        if(!(n.endsWith(".mp4")||n.endsWith(".3gp")||n.contains("video")||!n.contains(".")))return true;
        long len=f.length();if(len<16)return false;
        try(RandomAccessFile r=new RandomAccessFile(f,"r")){
            long pos=0;int atoms=0;
            while(pos+8<=len&&atoms++<10000){
                r.seek(pos);long size=r.readInt()&0xffffffffL;byte[] t=new byte[4];r.readFully(t);
                long header=8L;if(size==1L){if(pos+16>len)return false;size=r.readLong();header=16L;}else if(size==0L){size=len-pos;}
                if(size<header||pos+size>len)return false;pos+=size;if(pos==len)return true;
            }
            return pos==len;
        }catch(Throwable ignored){return false;}
    }

    /** Best-effort MP4 completeness hint. Does not replace decoder validation. */
    private static boolean hasMoovAtom(File f){
        long len=f.length();if(len<8)return false;
        long start=Math.max(0L,len-1024L*1024L);
        try(FileInputStream in=new FileInputStream(f)){
            long skip=start;while(skip>0){long z=in.skip(skip);if(z<=0)break;skip-=z;}
            byte[] buf=new byte[(int)Math.min(1024L*1024L,len-start)];int n=in.read(buf);
            if(n<=0)return false;
            for(int i=0;i+3<n;i++)if(buf[i]=='m'&&buf[i+1]=='o'&&buf[i+2]=='o'&&buf[i+3]=='v')return true;
        }catch(Throwable ignored){}
        return false;
    }
}
