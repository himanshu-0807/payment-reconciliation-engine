package dev.recon.common.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/** Filesystem-backed store for running without LocalStack (profile "local-fs") and in tests. */
public class LocalFsObjectStore implements ObjectStore {

    private final Path root;

    public LocalFsObjectStore(Path root) {
        this.root = root.toAbsolutePath();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("key escapes root: " + key);
        }
        return p;
    }

    @Override
    public void put(String key, Path file) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.copy(file, tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void put(String key, byte[] content) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, content);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public InputStream get(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public List<ObjectInfo> list(String prefix) throws IOException {
        Path dir = resolve(prefix.endsWith("/") ? prefix : prefix.substring(0, Math.max(0, prefix.lastIndexOf('/') + 1)));
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<ObjectInfo> out = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                String key = root.relativize(p).toString().replace('\\', '/');
                if (key.startsWith(prefix) && !key.endsWith(".tmp")) {
                    out.add(new ObjectInfo(key, Files.size(p), md5(p)));
                }
            }
        }
        out.sort(Comparator.comparing(ObjectInfo::key));
        return out;
    }

    @Override
    public void deletePrefix(String prefix) throws IOException {
        for (ObjectInfo o : list(prefix)) {
            Files.deleteIfExists(resolve(o.key()));
        }
    }

    @Override
    public String describe() {
        return "local-fs:" + root;
    }

    private static String md5(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) {
                md.update(buf, 0, r);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
