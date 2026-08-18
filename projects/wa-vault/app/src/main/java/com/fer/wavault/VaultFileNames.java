package com.fer.wavault;

import android.content.Context;
import java.io.File;
import java.util.Locale;
import java.util.UUID;

/** Opaque private filenames: user-facing names live only in encrypted database fields. */
public final class VaultFileNames {
    private VaultFileNames() {}
    private static final String PREFIX="wv_";
    private static final String SHARE_PREFIX="ws_";

    public static boolean isOpaqueName(String name){
        if(name==null)return false;
        String base=name;int dot=base.indexOf('.');if(dot>=0)base=base.substring(0,dot);
        if(!base.startsWith(PREFIX)||base.length()!=PREFIX.length()+32)return false;
        for(int i=PREFIX.length();i<base.length();i++){char c=base.charAt(i);if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')))return false;}
        return true;
    }

    public static String opaqueName(String displayName,String mime,String type){return PREFIX+token()+extension(displayName,mime,type);}
    public static String shareName(String displayName,String mime,String type){return SHARE_PREFIX+token()+extension(displayName,mime,type);}
    /** Staging names contain only random tokens plus the coarse media type required for crash recovery. */
    public static String stagingName(String prefix,String type){String p=prefix==null?"part_":prefix;String t=("video".equals(type)||"image".equals(type)||"audio".equals(type)||"document".equals(type))?type:"media";return p+token()+"_"+t+"_"+token().substring(0,8)+".bin";}

    public static File newOpaqueSibling(File source,String displayName,String mime,String type){
        if(source==null||source.getParentFile()==null)return null;
        for(int i=0;i<6;i++){File out=new File(source.getParentFile(),opaqueName(displayName,mime,type));if(!out.exists())return out;}
        return null;
    }

    /** New permanent archive files must become opaque before their DB row is committed. */
    public static File ensureNewArchiveOpaque(Context context,File source,String displayName,String mime,String type){
        if(source==null||!source.exists())return source;
        if(!isProtectedArchive(context,source)||isOpaqueName(source.getName()))return source;
        File out=newOpaqueSibling(source,displayName,mime,type);if(out==null)return null;
        try{return source.renameTo(out)?out:null;}catch(Throwable t){return null;}
    }

    public static boolean isProtectedArchive(Context context,File file){
        if(context==null||file==null||file.getParentFile()==null)return false;
        try{String parent=file.getParentFile().getCanonicalPath();String files=context.getFilesDir().getCanonicalPath();return parent.equals(new File(files,"vault_media").getCanonicalPath())||parent.equals(new File(files,"vault_audio_quarantine").getCanonicalPath());}catch(Throwable t){return false;}
    }

    public static String genericDisplayName(File file){return "WA Vault"+extension(file==null?"":file.getName(),null,null);}

    private static String token(){return UUID.randomUUID().toString().replace("-","").toLowerCase(Locale.ROOT);}
    private static String extension(String displayName,String mime,String type){
        String n=displayName==null?"":displayName.trim();int dot=n.lastIndexOf('.');
        if(dot>=0&&dot<n.length()-1){String e=n.substring(dot+1).toLowerCase(Locale.ROOT);if(e.matches("[a-z0-9]{1,10}"))return "."+e;}
        String m=mime==null?"":mime.toLowerCase(Locale.ROOT);
        if(m.contains("jpeg")||m.contains("jpg"))return ".jpg";if(m.contains("png"))return ".png";if(m.contains("webp"))return ".webp";if(m.contains("gif"))return ".gif";
        if(m.contains("opus"))return ".opus";if(m.contains("ogg"))return ".ogg";if(m.contains("mpeg"))return "audio".equals(type)?".mp3":".mpg";if(m.contains("wav"))return ".wav";if(m.contains("mp4"))return "audio".equals(type)?".m4a":".mp4";
        if(m.contains("pdf"))return ".pdf";if(m.contains("zip"))return ".zip";
        if("image".equals(type))return ".img";if("video".equals(type))return ".vid";if("audio".equals(type))return ".aud";if("document".equals(type))return ".docbin";return ".bin";
    }
}
