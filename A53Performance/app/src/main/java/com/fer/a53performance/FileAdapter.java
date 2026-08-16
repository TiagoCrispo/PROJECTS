package com.fer.a53performance;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FileAdapter extends RecyclerView.Adapter<FileAdapter.Holder> {
    public interface Listener { void onSelectionChanged(int count, long bytes); }
    private final ArrayList<StorageItem> items = new ArrayList<>();
    private final HashSet<String> selected = new HashSet<>();
    private final ThumbnailLoader thumbs;
    private final Listener listener;

    public FileAdapter(ThumbnailLoader thumbs, Listener listener) { this.thumbs = thumbs; this.listener = listener; setHasStableIds(true); }
    @Override public long getItemId(int position) { return items.get(position).stableKey().hashCode(); }
    @Override public int getItemCount() { return items.size(); }

    public void replace(List<StorageItem> list) { items.clear(); items.addAll(list); selected.retainAll(keys(list)); notifyDataSetChanged(); emitSelection(); }
    public void append(List<StorageItem> more) { if (more.isEmpty()) return; int start=items.size(); items.addAll(more); notifyItemRangeInserted(start, more.size()); }
    public void removeAll(Collection<StorageItem> gone) {
        Set<String> keys = keys(gone);
        for (int i=items.size()-1;i>=0;i--) if (keys.contains(items.get(i).stableKey())) { selected.remove(items.get(i).stableKey()); items.remove(i); notifyItemRemoved(i); }
        emitSelection();
    }
    public List<StorageItem> selectedItems() { ArrayList<StorageItem> out=new ArrayList<>(); for(StorageItem x:items) if(selected.contains(x.stableKey())) out.add(x); return out; }
    public void clearSelection(){ if(selected.isEmpty())return; selected.clear(); notifyDataSetChanged(); emitSelection(); }
    public int displayedCount(){return items.size();}
    private Set<String> keys(Collection<StorageItem> xs){ HashSet<String>s=new HashSet<>();for(StorageItem x:xs)s.add(x.stableKey());return s; }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int p=dp(parent,12);
        LinearLayout row=new LinearLayout(parent.getContext()); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(p,dp(parent,8),p,dp(parent,8));
        row.setBackgroundColor(Color.rgb(12,16,20));
        ImageView image=new ImageView(parent.getContext()); image.setScaleType(ImageView.ScaleType.CENTER_CROP); row.addView(image,new LinearLayout.LayoutParams(dp(parent,58),dp(parent,58)));
        LinearLayout text=new LinearLayout(parent.getContext());text.setOrientation(LinearLayout.VERTICAL);text.setPadding(dp(parent,12),0,dp(parent,8),0);row.addView(text,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        TextView title=new TextView(parent.getContext());title.setTextColor(Color.WHITE);title.setTextSize(15);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setMaxLines(1);text.addView(title);
        TextView sub=new TextView(parent.getContext());sub.setTextColor(Color.rgb(170,180,188));sub.setTextSize(12);sub.setMaxLines(2);text.addView(sub);
        CheckBox cb=new CheckBox(parent.getContext());cb.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.rgb(92,224,164)));row.addView(cb,new LinearLayout.LayoutParams(dp(parent,48),dp(parent,48)));
        return new Holder(row,image,title,sub,cb);
    }

    @Override public void onBindViewHolder(@NonNull Holder h,int position){
        StorageItem x=items.get(position);h.title.setText(x.name);h.sub.setText(formatBytes(x.size)+"  ·  "+type(x)+"\n"+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT, Locale.getDefault()).format(new Date(x.modified)));
        h.cb.setOnCheckedChangeListener(null);h.cb.setChecked(selected.contains(x.stableKey()));h.cb.setOnCheckedChangeListener((b,checked)->toggle(x,checked));
        h.itemView.setOnClickListener(v->{boolean next=!selected.contains(x.stableKey());h.cb.setChecked(next);});
        thumbs.load(x,h.image);
    }
    @Override public void onViewRecycled(@NonNull Holder h){h.image.setTag(null);h.image.setImageDrawable(null);super.onViewRecycled(h);}
    private void toggle(StorageItem x,boolean on){if(on)selected.add(x.stableKey());else selected.remove(x.stableKey());emitSelection();}
    private void emitSelection(){long bytes=0;for(StorageItem x:items)if(selected.contains(x.stableKey()))bytes+=x.size;listener.onSelectionChanged(selected.size(),bytes);}
    private static String type(StorageItem x){if(x.isImage())return "Imagen";if(x.isVideo())return "Video";if(x.isAudio())return "Audio";return x.mime.isBlank()?"Archivo":x.mime;}
    public static String formatBytes(long v){if(v<1024)return v+" B";double n=v;String[]u={"KB","MB","GB","TB"};int i=-1;do{n/=1024d;i++;}while(n>=1024&&i<u.length-1);return String.format(Locale.getDefault(),n>=10?"%.1f %s":"%.2f %s",n,u[i]);}
    private static int dp(View v,int n){return Math.round(n*v.getResources().getDisplayMetrics().density);}
    static final class Holder extends RecyclerView.ViewHolder{final ImageView image;final TextView title,sub;final CheckBox cb;Holder(View v,ImageView i,TextView t,TextView s,CheckBox c){super(v);image=i;title=t;sub=s;cb=c;}}
}
