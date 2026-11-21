package com.anubis.app;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** v4.38: serves exactly one file, the current invite QR PNG, read-only, so Copy QR can put an image on the clipboard.
 *  Not exported; other apps can read it only through the clipboard's temporary URI grant. */
public class QrProvider extends ContentProvider {
  static String auth(Context c) { return c.getPackageName() + ".qr"; }
  static File file(Context c) { File d = new File(c.getCacheDir(), "qr"); d.mkdirs(); return new File(d, "invite.png"); }
  static Uri uri(Context c) { return Uri.parse("content://" + auth(c) + "/invite.png"); }
  @Override public boolean onCreate() { return true; }
  @Override public String getType(Uri u) { return "image/png"; }
  @Override public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
    if (!"invite.png".equals(u.getLastPathSegment())) throw new FileNotFoundException();
    if (mode != null && mode.contains("w")) throw new FileNotFoundException("read-only");
    return ParcelFileDescriptor.open(file(getContext()), ParcelFileDescriptor.MODE_READ_ONLY);
  }
  @Override public Cursor query(Uri u, String[] proj, String sel, String[] args, String sort) {
    File f = file(getContext());
    MatrixCursor m = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
    m.addRow(new Object[]{"invite-qr.png", f.length()});
    return m;
  }
  @Override public Uri insert(Uri u, ContentValues v) { return null; }
  @Override public int delete(Uri u, String s, String[] a) { return 0; }
  @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
