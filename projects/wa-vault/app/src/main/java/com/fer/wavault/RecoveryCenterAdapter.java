package com.fer.wavault;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.Date;
import java.util.Locale;

/** Recovery Center 2.0 visual layer. It owns only row rendering and delegates actions. */
public final class RecoveryCenterAdapter extends ListAdapter<VaultDb.Media, RecoveryCenterAdapter.Holder> {
    public interface Callback {
        void onOpen(VaultDb.Media media);
        void onFavorite(VaultDb.Media media);
        void onShare(VaultDb.Media media);
        void onSave(VaultDb.Media media);
        void onTrash(VaultDb.Media media);
        void onToggleSelection(VaultDb.Media media);
        void onDetails(VaultDb.Media media);
        boolean isSelected(long mediaId);
        boolean selectionActive();
        String contextFor(long mediaId);
        String confidenceFor(VaultDb.Media media);
    }

    private static final DiffUtil.ItemCallback<VaultDb.Media> DIFF = new DiffUtil.ItemCallback<VaultDb.Media>() {
        @Override public boolean areItemsTheSame(@NonNull VaultDb.Media a,@NonNull VaultDb.Media b){return a.id==b.id;}
        @Override public boolean areContentsTheSame(@NonNull VaultDb.Media a,@NonNull VaultDb.Media b){
            return a.favorite==b.favorite && a.trashed==b.trashed && a.size==b.size && a.linkedMessageId==b.linkedMessageId &&
                    safe(a.path).equals(safe(b.path)) && safe(a.name).equals(safe(b.name)) && safe(a.origin).equals(safe(b.origin));
        }
    };

    private final Context context;
    private final Callback callback;
    private final int fg=Color.rgb(247,249,251), muted=Color.rgb(139,152,167), green=Color.rgb(92,224,164),
            card=Color.rgb(15,20,26), cardSoft=Color.rgb(20,27,35), selected=Color.rgb(20,53,40), line=Color.rgb(37,48,59);

    public RecoveryCenterAdapter(Context context,Callback callback){super(DIFF);this.context=context;this.callback=callback;setHasStableIds(true);}
    @Override public long getItemId(int position){return getItem(position).id;}
    public void notifyMediaChanged(long mediaId){for(int i=0;i<getCurrentList().size();i++)if(getCurrentList().get(i).id==mediaId){notifyItemChanged(i);return;}}

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent,int viewType){
        LinearLayout root=new LinearLayout(context);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(8),dp(8),dp(8),dp(8));root.setBackground(outline(card,line,20));
        FrameLayout frame=new FrameLayout(context);frame.setBackground(outline(cardSoft,line,16));
        TextView glyph=text("▣",28,green,true);glyph.setGravity(Gravity.CENTER);frame.addView(glyph,new FrameLayout.LayoutParams(-1,-1));
        ImageView preview=new ImageView(context);preview.setScaleType(ImageView.ScaleType.CENTER_CROP);preview.setClipToOutline(true);frame.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        root.addView(frame,new LinearLayout.LayoutParams(-1,dp(150)));
        TextView title=text("",13.5f,fg,true);title.setMaxLines(1);title.setEllipsize(TextUtils.TruncateAt.END);title.setPadding(0,dp(6),0,0);root.addView(title);
        TextView meta=text("",10.5f,muted,false);meta.setMaxLines(2);meta.setEllipsize(TextUtils.TruncateAt.END);root.addView(meta);
        TextView confidence=text("",10,green,true);confidence.setMaxLines(1);root.addView(confidence);
        LinearLayout actions=new LinearLayout(context);actions.setOrientation(LinearLayout.HORIZONTAL);actions.setPadding(0,dp(4),0,0);
        Button fav=button("☆"),share=button("↗"),save=button("↓"),trash=button("🗑");
        add(actions,fav);add(actions,share);add(actions,save);add(actions,trash);root.addView(actions);
        return new Holder(root,preview,glyph,title,meta,confidence,fav,share,save,trash);
    }

    @Override public void onBindViewHolder(@NonNull Holder h,int position){
        VaultDb.Media m=getItem(position);String type=safe(m.type);String name=safe(m.name).isEmpty()?friendly(type):m.name;File f=safe(m.path).isEmpty()?null:new File(m.path);boolean available=f!=null&&f.exists();
        h.root.setBackground(outline(callback.isSelected(m.id)?selected:card,callback.isSelected(m.id)?green:line,20));h.root.setAlpha(available?1f:.5f);
        h.title.setText((m.favorite?"★ ":"")+name);h.favorite.setText(m.favorite?"★":"☆");
        String ctx=callback.contextFor(m.id);String detail=typeLabel(type)+" · "+human(m.size)+" · "+DateFormat.format("dd/MM HH:mm",new Date(m.capturedAt));if(ctx!=null&&!ctx.isEmpty())detail+=" · "+ctx;h.meta.setText(detail);
        String conf=callback.confidenceFor(m);boolean quiet=conf!=null&&conf.startsWith("Asociación: alta");h.confidence.setText(quiet?"Detalles":conf);h.confidence.setTextColor(quiet?muted:green);
        h.preview.setImageDrawable(null);h.preview.setTag(null);h.glyph.setText("audio".equals(type)?"♪":("video".equals(type)?"▶":("document".equals(type)?"DOC":"▣")));
        if(available && ("image".equals(type)||"video".equals(type)))MediaThumbnailLoader.load(context,h.preview,f,type,dp(320));
        boolean selecting=callback.selectionActive();
        h.root.setOnClickListener(v->{if(callback.selectionActive())callback.onToggleSelection(m);else callback.onOpen(m);});
        h.confidence.setOnClickListener(v->{if(callback.selectionActive())callback.onToggleSelection(m);else callback.onDetails(m);});
        h.root.setOnLongClickListener(v->{callback.onToggleSelection(m);return true;});
        h.favorite.setOnClickListener(v->{if(callback.selectionActive())callback.onToggleSelection(m);else callback.onFavorite(m);});
        h.share.setOnClickListener(v->{if(callback.selectionActive())callback.onToggleSelection(m);else callback.onShare(m);});
        h.save.setOnClickListener(v->{if(callback.selectionActive())callback.onToggleSelection(m);else callback.onSave(m);});
        h.trash.setOnClickListener(v->{if(callback.selectionActive())callback.onToggleSelection(m);else callback.onTrash(m);});
    }

    static final class Holder extends RecyclerView.ViewHolder{
        final LinearLayout root;final ImageView preview;final TextView glyph,title,meta,confidence;final Button favorite,share,save,trash;
        Holder(LinearLayout root,ImageView preview,TextView glyph,TextView title,TextView meta,TextView confidence,Button favorite,Button share,Button save,Button trash){super(root);this.root=root;this.preview=preview;this.glyph=glyph;this.title=title;this.meta=meta;this.confidence=confidence;this.favorite=favorite;this.share=share;this.save=save;this.trash=trash;}
    }

    private TextView text(String s,float sp,int color,boolean bold){TextView t=new TextView(context);t.setText(s);t.setTextSize(sp);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private Button button(String s){Button b=new Button(context);b.setText(s);b.setTextColor(fg);b.setTextSize(13);b.setAllCaps(false);b.setMinHeight(0);b.setMinWidth(0);b.setPadding(dp(2),0,dp(2),0);b.setBackground(round(cardSoft,10));return b;}
    private void add(LinearLayout row,View v){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(38),1f);lp.setMargins(dp(2),0,dp(2),0);row.addView(v,lp);}
    private GradientDrawable round(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private GradientDrawable outline(int color,int stroke,int radius){GradientDrawable g=round(color,radius);g.setStroke(dp(1),stroke);return g;}
    private int dp(int x){return Math.max(1,Math.round(x*context.getResources().getDisplayMetrics().density));}
    private static String safe(String s){return s==null?"":s;}
    private static String friendly(String type){if("image".equals(type))return "Foto de WhatsApp";if("video".equals(type))return "Video de WhatsApp";if("audio".equals(type))return "Audio de WhatsApp";if("document".equals(type))return "Documento de WhatsApp";return "Archivo de WhatsApp";}
    private static String typeLabel(String type){if("image".equals(type))return "Foto";if("video".equals(type))return "Video";if("audio".equals(type))return "Audio";if("document".equals(type))return "Documento";return "Archivo";}
    private static String human(long b){if(b<1024)return b+" B";double k=b/1024d;if(k<1024)return String.format(Locale.ROOT,"%.1f KB",k);double m=k/1024d;if(m<1024)return String.format(Locale.ROOT,"%.1f MB",m);return String.format(Locale.ROOT,"%.2f GB",m/1024d);}
}
