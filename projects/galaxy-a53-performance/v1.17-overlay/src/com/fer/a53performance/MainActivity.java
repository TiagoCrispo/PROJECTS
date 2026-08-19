package com.fer.a53performance;

import android.app.Activity;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** v1.17 Gallery 2 overlay. */
public class MainActivity extends BaseActivity {
    private static final int REQ_TRASH = 8417;
    private static final int BATCH_SIZE = 80;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbs = Executors.newFixedThreadPool(3);
    private final List<Gallery2Item> allItems = new ArrayList<Gallery2Item>();
    private final List<Gallery2Item> visibleItems = new ArrayList<Gallery2Item>();
    private final Set<String> selected = new HashSet<String>();
    private final List<List<Uri>> trashBatches = new ArrayList<List<Uri>>();

    private Dialog galleryDialog;
    private GridView grid;
    private Gallery2Adapter adapter;
    private TextView summaryText;
    private TextView selectionText;
    private ProgressBar progress;
    private Spinner filterSpinner;
    private Spinner sortSpinner;
    private int trashBatchIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addGalleryEntry();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        thumbs.shutdownNow();
        super.onDestroy();
    }

    private void addGalleryEntry() {
        View content = findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;

        Button button = new Button(this);
        button.setText("Fotos · limpiar");
        button.setTextColor(Color.WHITE);
        button.setTextSize(14f);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setElevation(dp(8));
        button.setContentDescription("Abrir Limpieza Visual de fotos y videos");
        button.setBackground(roundRect(0xFF2367E8, dp(24)));
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showGallery2(); }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52), Gravity.END | Gravity.BOTTOM);
        lp.setMargins(dp(16), dp(16), dp(16), dp(80));

        if (content instanceof FrameLayout) {
            ((FrameLayout) content).addView(button, lp);
        } else {
            FrameLayout overlay = new FrameLayout(this);
            overlay.setClickable(false);
            ((ViewGroup) content).addView(overlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            overlay.addView(button, lp);
        }
    }

    private void showGallery2() {
        selected.clear();
        galleryDialog = new Dialog(this, android.R.style.Theme_Material_NoActionBar);
        galleryDialog.setContentView(buildGalleryView());
        galleryDialog.setOnShowListener(dialog -> {
            Window w = galleryDialog.getWindow();
            if (w != null) w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        });
        galleryDialog.show();
        Window window = galleryDialog.getWindow();
        if (window != null) {
            window.setStatusBarColor(0xFF0D1117);
            window.setNavigationBarColor(0xFF0D1117);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        loadMedia();
    }

    private View buildGalleryView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D1117);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Limpieza Visual 2.0");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(56), 1f));
        Button close = smallButton("Cerrar");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (galleryDialog != null) galleryDialog.dismiss(); }
        });
        header.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        root.addView(header);

        summaryText = new TextView(this);
        summaryText.setText("Buscando fotos y videos…");
        summaryText.setTextColor(0xFF9AA4B2);
        summaryText.setTextSize(13f);
        summaryText.setPadding(0, 0, 0, dp(8));
        root.addView(summaryText);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        filterSpinner = new Spinner(this);
        String[] filters = new String[]{"Todas", "Capturas", "WhatsApp", "Descargas", "Videos >500 MB", "Antiguas >1 año"};
        ArrayAdapter<String> f = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, filters);
        f.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filterSpinner.setAdapter(f);
        controls.addView(filterSpinner, new LinearLayout.LayoutParams(0, dp(48), 1f));
        sortSpinner = new Spinner(this);
        String[] sorts = new String[]{"Recientes", "Más pesadas", "Más antiguas"};
        ArrayAdapter<String> s = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, sorts);
        s.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sortSpinner.setAdapter(s);
        controls.addView(sortSpinner, new LinearLayout.LayoutParams(0, dp(48), 1f));
        root.addView(controls);

        AdapterView.OnItemSelectedListener selectedListener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { applyFilterAndSort(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
        filterSpinner.setOnItemSelectedListener(selectedListener);
        sortSpinner.setOnItemSelectedListener(selectedListener);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(dp(3));
        grid.setVerticalSpacing(dp(3));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(6), 0, dp(6));
        adapter = new Gallery2Adapter(this, visibleItems, selected, thumbs);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= visibleItems.size()) return;
                Gallery2Item item = visibleItems.get(position);
                String key = item.uri.toString();
                if (selected.contains(key)) selected.remove(key); else selected.add(key);
                adapter.notifyDataSetChanged();
                updateSelectionBar();
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < visibleItems.size()) {
                    showPreview(visibleItems.get(position));
                    return true;
                }
                return false;
            }
        });
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout action = new LinearLayout(this);
        action.setOrientation(LinearLayout.VERTICAL);
        action.setPadding(0, dp(6), 0, 0);
        selectionText = new TextView(this);
        selectionText.setText("Nada seleccionado");
        selectionText.setTextColor(Color.WHITE);
        selectionText.setTextSize(14f);
        selectionText.setGravity(Gravity.CENTER_VERTICAL);
        action.addView(selectionText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button selectVisible = smallButton("Seleccionar visibles");
        selectVisible.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                for (Gallery2Item item : visibleItems) selected.add(item.uri.toString());
                adapter.notifyDataSetChanged();
                updateSelectionBar();
            }
        });
        buttons.addView(selectVisible, new LinearLayout.LayoutParams(0, dp(48), 1f));
        Button clear = smallButton("Quitar selección");
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        clearLp.setMargins(dp(6), 0, dp(6), 0);
        buttons.addView(clear, clearLp);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                selected.clear();
                adapter.notifyDataSetChanged();
                updateSelectionBar();
            }
        });
        Button trash = smallButton("Mover a papelera");
        trash.setTextColor(Color.WHITE);
        trash.setBackground(roundRect(0xFFD43F4D, dp(12)));
        trash.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { beginTrashSelected(); }
        });
        buttons.addView(trash, new LinearLayout.LayoutParams(0, dp(48), 1f));
        action.addView(buttons);
        root.addView(action);
        return root;
    }

    private void loadMedia() {
        if (progress != null) progress.setVisibility(View.VISIBLE);
        if (summaryText != null) summaryText.setText("Buscando fotos y videos…");
        io.execute(new Runnable() {
            @Override public void run() {
                final List<Gallery2Item> loaded = queryMedia();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        allItems.clear();
                        allItems.addAll(loaded);
                        if (progress != null) progress.setVisibility(View.GONE);
                        applyFilterAndSort();
                    }
                });
            }
        });
    }

    private List<Gallery2Item> queryMedia() {
        List<Gallery2Item> result = new ArrayList<Gallery2Item>();
        ContentResolver cr = getContentResolver();
        Uri files = MediaStore.Files.getContentUri("external");
        String[] projection = new String[]{
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.MIME_TYPE,
                Build.VERSION.SDK_INT >= 29 ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.MediaColumns.DATA
        };
        String selectionClause = MediaStore.Files.FileColumns.MEDIA_TYPE + "=? OR " + MediaStore.Files.FileColumns.MEDIA_TYPE + "=?";
        String[] args = new String[]{String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE), String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)};
        Cursor c = null;
        try {
            c = cr.query(files, projection, selectionClause, args, MediaStore.MediaColumns.DATE_ADDED + " DESC");
            if (c == null) return result;
            int idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
            int typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE);
            int nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);
            int mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);
            int pathCol = c.getColumnIndex(projection[6]);
            while (c.moveToNext()) {
                long id = c.getLong(idCol);
                int mediaType = c.getInt(typeCol);
                String name = safe(c.getString(nameCol));
                long size = Math.max(0L, c.getLong(sizeCol));
                long date = Math.max(0L, c.getLong(dateCol)) * 1000L;
                String mime = safe(c.getString(mimeCol));
                String path = pathCol >= 0 ? safe(c.getString(pathCol)) : "";
                boolean video = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
                Uri uri = Uri.withAppendedPath(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
                result.add(new Gallery2Item(uri, name, path, mime, size, date, video));
            }
        } catch (Throwable t) {
            final String msg = t.getClass().getSimpleName();
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Toast.makeText(MainActivity.this, "No pude leer la galería: " + msg + ". Revisa permisos de fotos/videos.", Toast.LENGTH_LONG).show();
                }
            });
        } finally {
            if (c != null) c.close();
        }
        return result;
    }

    private void applyFilterAndSort() {
        if (adapter == null || filterSpinner == null || sortSpinner == null) return;
        visibleItems.clear();
        int filter = filterSpinner.getSelectedItemPosition();
        long now = System.currentTimeMillis();
        long year = 365L * 24L * 60L * 60L * 1000L;
        long totalBytes = 0L;
        for (Gallery2Item item : allItems) {
            String lowerPath = item.path.toLowerCase(Locale.ROOT);
            boolean keep;
            switch (filter) {
                case 1: keep = lowerPath.contains("screenshot") || lowerPath.contains("captura"); break;
                case 2: keep = lowerPath.contains("whatsapp") || item.name.toLowerCase(Locale.ROOT).contains("whatsapp"); break;
                case 3: keep = lowerPath.contains("download") || lowerPath.contains("descarga"); break;
                case 4: keep = item.video && item.size >= 500L * 1024L * 1024L; break;
                case 5: keep = item.dateMs > 0L && now - item.dateMs >= year; break;
                default: keep = true;
            }
            if (keep) { visibleItems.add(item); totalBytes += item.size; }
        }
        int sort = sortSpinner.getSelectedItemPosition();
        if (sort == 1) {
            Collections.sort(visibleItems, new Comparator<Gallery2Item>() { @Override public int compare(Gallery2Item a, Gallery2Item b) { return Long.compare(b.size, a.size); } });
        } else if (sort == 2) {
            Collections.sort(visibleItems, new Comparator<Gallery2Item>() { @Override public int compare(Gallery2Item a, Gallery2Item b) { return Long.compare(a.dateMs, b.dateMs); } });
        } else {
            Collections.sort(visibleItems, new Comparator<Gallery2Item>() { @Override public int compare(Gallery2Item a, Gallery2Item b) { return Long.compare(b.dateMs, a.dateMs); } });
        }
        adapter.notifyDataSetChanged();
        summaryText.setText(visibleItems.size() + " elementos · " + humanBytes(totalBytes) + " · Mantén pulsado para ampliar");
        updateSelectionBar();
    }

    private void updateSelectionBar() {
        long bytes = 0L;
        int count = 0;
        for (Gallery2Item item : allItems) {
            if (selected.contains(item.uri.toString())) { count++; bytes += item.size; }
        }
        if (count == 0) selectionText.setText("Nada seleccionado · no se borra automáticamente");
        else selectionText.setText(count + " seleccionados · " + humanBytes(bytes));
    }

    private void beginTrashSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "Selecciona fotos o videos primero.", Toast.LENGTH_SHORT).show();
            return;
        }
        List<Uri> uris = new ArrayList<Uri>();
        for (Gallery2Item item : allItems) if (selected.contains(item.uri.toString())) uris.add(item.uri);
        if (uris.isEmpty()) return;
        if (Build.VERSION.SDK_INT < 30) {
            int deleted = 0;
            for (Uri uri : uris) {
                try { if (getContentResolver().delete(uri, null, null) > 0) deleted++; } catch (Throwable ignored) { }
            }
            Toast.makeText(this, "Eliminados: " + deleted + ".", Toast.LENGTH_LONG).show();
            loadMedia();
            return;
        }
        trashBatches.clear();
        for (int i = 0; i < uris.size(); i += BATCH_SIZE) trashBatches.add(new ArrayList<Uri>(uris.subList(i, Math.min(i + BATCH_SIZE, uris.size()))));
        trashBatchIndex = 0;
        startNextTrashBatch();
    }

    private void startNextTrashBatch() {
        if (trashBatchIndex >= trashBatches.size()) {
            Toast.makeText(this, "Papelera actualizada.", Toast.LENGTH_SHORT).show();
            selected.clear();
            loadMedia();
            return;
        }
        try {
            PendingIntent request = MediaStore.createTrashRequest(getContentResolver(), trashBatches.get(trashBatchIndex), true);
            startIntentSenderForResult(request.getIntentSender(), REQ_TRASH, null, 0, 0, 0);
        } catch (Throwable t) {
            Toast.makeText(this, "No se pudo abrir la Papelera del sistema: " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TRASH) {
            if (resultCode == Activity.RESULT_OK) {
                trashBatchIndex++;
                startNextTrashBatch();
            } else {
                Toast.makeText(this, "Operación cancelada. No se tocó el resto de la selección.", Toast.LENGTH_SHORT).show();
                loadMedia();
            }
        }
    }

    private void showPreview(final Gallery2Item item) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Material_NoActionBar);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        final ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView info = new TextView(this);
        info.setText(item.name + "\n" + humanBytes(item.size) + " · " + (item.video ? "Video" : "Foto") + "\n" + item.path);
        info.setTextColor(Color.WHITE);
        info.setPadding(dp(16), dp(10), dp(16), dp(10));
        root.addView(info);
        Button done = smallButton("Volver");
        done.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } });
        root.addView(done, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        d.setContentView(root);
        d.show();
        Window w = d.getWindow();
        if (w != null) w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            thumbs.execute(new Runnable() {
                @Override public void run() {
                    try {
                        final Bitmap bmp = getContentResolver().loadThumbnail(item.uri, new Size(1080, 1080), null);
                        runOnUiThread(new Runnable() { @Override public void run() { if (d.isShowing()) image.setImageBitmap(bmp); } });
                    } catch (Throwable ignored) { }
                }
            });
        }
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12f);
        b.setAllCaps(false);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setBackground(roundRect(0xFF202832, dp(12)));
        return b;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String safe(String s) { return s == null ? "" : s; }
    private static String humanBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double v = bytes;
        String[] units = new String[]{"KB", "MB", "GB", "TB"};
        int idx = -1;
        do { v /= 1024.0; idx++; } while (v >= 1024.0 && idx < units.length - 1);
        return new DecimalFormat(v >= 10.0 ? "0.0" : "0.00").format(v) + " " + units[idx];
    }
}

final class Gallery2Item {
    final Uri uri; final String name; final String path; final String mime; final long size; final long dateMs; final boolean video;
    Gallery2Item(Uri uri, String name, String path, String mime, long size, long dateMs, boolean video) {
        this.uri = uri; this.name = name; this.path = path; this.mime = mime; this.size = size; this.dateMs = dateMs; this.video = video;
    }
}

final class Gallery2Adapter extends BaseAdapter {
    private final MainActivity activity;
    private final List<Gallery2Item> items;
    private final Set<String> selected;
    private final ExecutorService thumbs;
    private final LruCache<String, Bitmap> cache;
    private final int cellHeight;

    Gallery2Adapter(MainActivity activity, List<Gallery2Item> items, Set<String> selected, ExecutorService thumbs) {
        this.activity = activity; this.items = items; this.selected = selected; this.thumbs = thumbs; this.cellHeight = dp(122);
        int maxKb = (int) Math.min(24L * 1024L, Runtime.getRuntime().maxMemory() / 1024L / 8L);
        this.cache = new LruCache<String, Bitmap>(Math.max(4096, maxKb)) {
            @Override protected int sizeOf(String key, Bitmap value) { return Math.max(1, value.getByteCount() / 1024); }
        };
    }
    @Override public int getCount() { return items.size(); }
    @Override public Object getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return position; }
    @Override public View getView(int position, View convertView, ViewGroup parent) {
        Gallery2Cell cell = convertView instanceof Gallery2Cell ? (Gallery2Cell) convertView : new Gallery2Cell(activity, cellHeight);
        final Gallery2Item item = items.get(position);
        cell.bind(item, selected.contains(item.uri.toString()));
        final String key = item.uri.toString();
        Bitmap cached = cache.get(key);
        if (cached != null) {
            cell.image.setImageBitmap(cached);
        } else {
            cell.image.setImageDrawable(null);
            final Gallery2Cell target = cell;
            thumbs.execute(new Runnable() {
                @Override public void run() {
                    if (Build.VERSION.SDK_INT < 29) return;
                    try {
                        final Bitmap bmp = activity.getContentResolver().loadThumbnail(item.uri, new Size(360, 360), null);
                        cache.put(key, bmp);
                        activity.runOnUiThread(new Runnable() {
                            @Override public void run() { if (key.equals(target.getTag())) target.image.setImageBitmap(bmp); }
                        });
                    } catch (Throwable ignored) { }
                }
            });
        }
        return cell;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}

final class Gallery2Cell extends FrameLayout {
    final ImageView image; final TextView badge; final TextView meta;
    Gallery2Cell(Activity activity, int height) {
        super(activity);
        setLayoutParams(new android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        setBackgroundColor(0xFF161B22);
        image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        meta = new TextView(activity);
        meta.setTextColor(Color.WHITE); meta.setTextSize(10f); meta.setGravity(Gravity.CENTER_VERTICAL); meta.setPadding(dp(activity, 5), 0, dp(activity, 5), 0);
        GradientDrawable metaBg = new GradientDrawable(); metaBg.setColor(0x99000000); meta.setBackground(metaBg);
        addView(meta, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 24), Gravity.BOTTOM));
        badge = new TextView(activity);
        badge.setText("✓"); badge.setTextColor(Color.WHITE); badge.setTextSize(18f); badge.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(); bg.setShape(GradientDrawable.OVAL); bg.setColor(0xFF2367E8); badge.setBackground(bg);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(activity, 34), dp(activity, 34), Gravity.END | Gravity.TOP);
        bp.setMargins(0, dp(activity, 6), dp(activity, 6), 0); addView(badge, bp);
    }
    void bind(Gallery2Item item, boolean isSelected) {
        setTag(item.uri.toString()); setAlpha(isSelected ? 0.72f : 1f); badge.setVisibility(isSelected ? View.VISIBLE : View.GONE);
        meta.setText((item.video ? "VIDEO · " : "") + human(item.size));
        setContentDescription(item.name + ", " + human(item.size) + (isSelected ? ", seleccionado" : ", no seleccionado"));
    }
    private static int dp(Activity a, int value) { return Math.round(value * a.getResources().getDisplayMetrics().density); }
    private static String human(long bytes) {
        if (bytes < 1024L * 1024L) return Math.max(1L, bytes / 1024L) + " KB";
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}
