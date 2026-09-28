import java.io.*;
import java.util.zip.*;

// Packs directories into a jar without needing the JDK's jar tool. Entries are written in a
// fixed (sorted) order. args: out.jar dir [dir ...]
public class MakeJar {
    static void add(ZipOutputStream out, File base, File f) throws IOException {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            java.util.Arrays.sort(children);
            for (File c : children) {
                add(out, base, c);
            }
            return;
        }
        String name = base.toURI().relativize(f.toURI()).getPath();
        out.putNextEntry(new ZipEntry(name));
        FileInputStream in = new FileInputStream(f);
        byte[] b = new byte[8192];
        int r;
        while ((r = in.read(b)) > 0) {
            out.write(b, 0, r);
        }
        in.close();
        out.closeEntry();
    }

    public static void main(String[] a) throws Exception {
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(a[0]));
        out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
        out.write("Manifest-Version: 1.0\r\n\r\n".getBytes("UTF-8"));
        out.closeEntry();
        for (int i = 1; i < a.length; i++) {
            File d = new File(a[i]);
            add(out, d, d);
        }
        out.close();
    }
}
