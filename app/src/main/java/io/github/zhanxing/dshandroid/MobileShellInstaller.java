package io.github.zhanxing.dshandroid;

import android.content.Context;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Installs only our immutable UI addon under app-private files. Never edits a user profile. */
final class MobileShellInstaller {
  static Path install(Context context) throws Exception {
    Path root = context.getFilesDir().toPath().resolve("addons/mobile-shell");
    for (String relative : new String[]{"package.json", "lib/index.js", "lib/client.js"}) {
      byte[] bytes;
      try (InputStream input = context.getAssets().open("addons/mobile-shell/" + relative);
           ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192]; int n;
        while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
        bytes = output.toByteArray();
      }
      write(root.resolve(relative), bytes);
    }
    JSONObject entry = new JSONObject();
    entry.put("id", "android-mobile-shell");
    entry.put("name", root.resolve("lib/index.js").toString());
    JSONObject insertion = new JSONObject();
    insertion.put("insert", new JSONArray().put(entry));
    Path patch = root.resolve("android.patch.json");
    // JSON is a strict YAML subset accepted by the official --patch loader.
    write(patch, (new JSONArray().put(insertion).toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
    return patch;
  }
  private static void write(Path destination, byte[] content) throws IOException {
    Files.createDirectories(destination.getParent());
    if (Files.isRegularFile(destination) && java.util.Arrays.equals(Files.readAllBytes(destination), content)) return;
    Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
    Files.write(temporary, content);
    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }
  private MobileShellInstaller() {}
}
