from pathlib import Path
import re, sys
root=Path(sys.argv[1])
p=root/'app/src/main/java/com/fer/wavault/MainActivity.java'
s=p.read_text()

# Button system: no fixed 46dp box, larger touch targets, no touch animation conflicts.
if 'import android.text.TextUtils;' not in s:
    s=s.replace('import android.text.format.DateFormat;','import android.text.TextUtils;\nimport android.text.format.DateFormat;')
new_button='''    private Button button(String s, View.OnClickListener l) {
        Button b=new Button(this);
        b.setText(s == null ? "" : s);
        b.setTextColor(fg);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setMaxLines(2);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setMinimumWidth(0);
        b.setMinWidth(0);
        b.setMinimumHeight(dp(52));
        b.setMinHeight(dp(52));
        b.setPadding(dp(14),dp(10),dp(14),dp(10));
        b.setClickable(true);
        b.setFocusable(true);
        GradientDrawable base = outlined(cardSoft,line,14);
        if (Build.VERSION.SDK_INT >= 21) b.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(52,63,220,135)), base, null));
        else b.setBackground(base);
        b.setOnClickListener(v->{
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            if (l != null) l.onClick(v);
        });
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(dp(3),dp(4),dp(3),dp(4));
        b.setLayoutParams(p);
        return b;
    }
'''
s=re.sub(r'    private Button button\(String s, View\.OnClickListener l\) \{.*?\n    \}\n(?=    private Button navButton)',new_button,s,count=1,flags=re.S)
s=s.replace('private LinearLayout row(){ LinearLayout r=new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); return r; }\n    private void addWeighted(LinearLayout r, View v){ LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1f); p.setMargins(dp(3),0,dp(3),0); r.addView(v,p); }','private LinearLayout row(){ LinearLayout r=new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); r.setClipChildren(false); return r; }\n    private void addWeighted(LinearLayout r, View v){\n        if(v instanceof Button){ ((Button)v).setTextSize(12); ((Button)v).setMaxLines(2); ((Button)v).setMinHeight(dp(52)); }\n        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1f);\n        p.setMargins(dp(3),dp(2),dp(3),dp(2));\n        r.addView(v,p);\n    }')

# Remove Media as a destination. Keep media capture internally and render recovered files inside messages.
s=s.replace('else if ("media".equals(currentScreen)) showMedia();','else if ("media".equals(currentScreen)) { currentScreen="messages"; showMessages(); }')
s=s.replace('        addWeighted(nav,navButton("Medios","media",this::showMedia));\n','',1)
s=s.replace('deleted+" mensajes borrados · "+medias.size()+" medios · "+human(total)','deleted+" mensajes borrados · "+medias.size()+" archivos recuperados · "+human(total)')

old_filters='''        LinearLayout filters=row();
        addWeighted(filters,button(filterLabel("all","Todos"),v->{messageFilter="all";showMessages();}));
        addWeighted(filters,button(filterLabel("text","Texto"),v->{messageFilter="text";showMessages();}));
        addWeighted(filters,button(filterLabel("audio","Audio"),v->{messageFilter="audio";showMessages();}));
        addWeighted(filters,button(filterLabel("image","Fotos"),v->{messageFilter="image";showMessages();}));
        addWeighted(filters,button(filterLabel("video","Video"),v->{messageFilter="video";showMessages();}));
        content.addView(filters);
'''
new_filters='''        LinearLayout filtersTop=row();
        addWeighted(filtersTop,button(filterLabel("all","Todos"),v->{messageFilter="all";showMessages();}));
        addWeighted(filtersTop,button(filterLabel("text","Texto"),v->{messageFilter="text";showMessages();}));
        addWeighted(filtersTop,button(filterLabel("audio","Audio"),v->{messageFilter="audio";showMessages();}));
        content.addView(filtersTop);
        LinearLayout filtersBottom=row();
        addWeighted(filtersBottom,button(filterLabel("image","Fotos"),v->{messageFilter="image";showMessages();}));
        addWeighted(filtersBottom,button(filterLabel("video","Videos"),v->{messageFilter="video";showMessages();}));
        content.addView(filtersBottom);
'''
s=s.replace(old_filters,new_filters)

for a,b in {
'Los archivos asociados se conservan en Medios salvo que los elimines por separado.':'Los archivos recuperados asociados se conservan hasta que los elimines.',
'Mantén pulsado el archivo en Medios para eliminarlo':'Mantén pulsado el archivo para eliminarlo',
'Motor reiniciado · revisando mensajes y medios':'Motor reiniciado · revisando mensajes y archivos',
'Textos y medios nuevos se protegen con AES-GCM':'Textos y archivos nuevos se protegen con AES-GCM',
'Cifrado de medios nuevos: ACTIVO':'Cifrado de archivos nuevos: ACTIVO',
'Permisos multimedia · ':'Permisos de archivos · ',
'Pedir permisos multimedia':'Pedir permisos de fotos, videos y audio',
'Permisos de medios concedidos':'Permisos de archivos concedidos',
'Algunos permisos de medios siguen desactivados':'Algunos permisos de archivos siguen desactivados',
'Borrar todos los medios':'Borrar todos los archivos',
'Borrar medios >30 días':'Borrar archivos >30 días',
'Borrar medios >90 días':'Borrar archivos >90 días',
'¿Eliminar las copias de medios de más de ':'¿Eliminar las copias de archivos de más de ',
'¿Eliminar todas las copias de fotos, videos y audios?':'¿Eliminar todas las copias recuperadas de fotos, videos y audios?',
'"Nuevos medios copiados: " + copied':'"Nuevos archivos copiados: " + copied'
}.items(): s=s.replace(a,b)

s=s.replace('new LinearLayout.LayoutParams(dp(52),dp(48))','new LinearLayout.LayoutParams(dp(56),dp(56))')

old_clean='''        LinearLayout clean=row();
        addWeighted(clean,button("Borrar >30 días",v->confirmDeleteOldMedia(30)));
        addWeighted(clean,button("Borrar todos los archivos",v->confirmClear(true)));
        content.addView(clean);
    }

    private void confirmDeleteOldMedia(int days){
'''
new_clean='''        content.addView(button("Gestionar archivos recuperados",v->showStorageManager()));
    }

    private void showStorageManager(){
        List<VaultDb.Media> all=safeMedia(5000);
        long total=0,audio=0,image=0,video=0;
        for(VaultDb.Media m:all){long n=Math.max(0,m.size);total+=n;if("audio".equals(m.type))audio+=n;else if("image".equals(m.type))image+=n;else if("video".equals(m.type))video+=n;}
        String msg="WA Vault usa "+human(total)+"\\n\\nFotos  "+human(image)+"\\nVideos  "+human(video)+"\\nAudio  "+human(audio)+"\\n\\nLas limpiezas solo borran copias de WA Vault; no modifican WhatsApp.";
        String[] opts={"Borrar archivos >30 días","Borrar archivos >90 días","Limpiar caché de miniaturas","Borrar todos los archivos"};
        new AlertDialog.Builder(this).setTitle("Liberar espacio").setMessage(msg).setItems(opts,(d,w)->{
            if(w==0)confirmDeleteOldMedia(30);
            else if(w==1)confirmDeleteOldMedia(90);
            else if(w==2){MediaThumbnailLoader.clearDiskCache(this);Toast.makeText(this,"Caché de miniaturas limpiada",Toast.LENGTH_SHORT).show();}
            else if(w==3)confirmClear(true);
        }).setNegativeButton("Cerrar",null).show();
    }

    private void confirmDeleteOldMedia(int days){
'''
s=s.replace(old_clean,new_clean)
s=s.replace('if(media){ for(VaultDb.Media m:safeMedia(2000)) if(m.path != null) new File(m.path).delete(); db.clearMedia(); showMedia(); }','if(media){ for(VaultDb.Media m:safeMedia(2000)) if(m.path != null) new File(m.path).delete(); db.clearMedia(); showSettings(); }')
s=s.replace('if (navigateToMedia) showMedia();\n                else if ("home".equals(currentScreen)) showHome();','if ("messages".equals(currentScreen)) showMessages();\n                else if ("home".equals(currentScreen)) showHome();')
p.write_text(s)

# Version 0.3.3
b=root/'app/build.gradle.kts'
bs=b.read_text().replace('versionCode = 32','versionCode = 33').replace('versionName = "0.3.2"','versionName = "0.3.3"')
b.write_text(bs)
m=root/'app/src/main/AndroidManifest.xml'
ms=m.read_text().replace('android:versionCode="32" android:versionName="0.3.2"','android:versionCode="33" android:versionName="0.3.3"')
m.write_text(ms)
print('WA Vault v0.3.3 UI patch applied')
