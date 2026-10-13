package com.awt.helper

import java.nio.file.*

object Server {
    fun browserUrl(c: HelperConfig): String = "http://${c.host}:${c.port}" + normalizedSitePath(c.hostnamePath).let { if(it.isEmpty()) "/" else "$it/" }
    private fun normalizedSitePath(path: String): String {
        require(path.startsWith('/') && path.none { it == '\\' || it == '\n' || it == '\r' || it == '?' || it == '#' } && !path.contains("..")) { "Invalid AWT hostname URL path." }
        return path.trimEnd('/')
    }
    fun arguments(root: Path, c: HelperConfig): List<String> {
        val sitePath=normalizedSitePath(c.hostnamePath)
        if(sitePath.isEmpty()) return Commands.server(c)
        // PHP's built-in server doesn't mount a URL subfolder. This local router maps that
        // prefix to public/ without changing the framework or interpreting it as a directory.
        val key=java.security.MessageDigest.getInstance("SHA-256").digest(sitePath.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
        val router=root.resolve(".awt/helper/local-router-$key.php")
        Files.createDirectories(router.parent)
        val source="""
            <?php
            ${'$'}prefix = ${phpString(sitePath)};
            ${'$'}uri = ${'$'}_SERVER['REQUEST_URI'] ?? '/';
            ${'$'}path = parse_url(${'$'}uri, PHP_URL_PATH) ?: '/';
            if (${'$'}path === ${'$'}prefix || str_starts_with(${'$'}path, ${'$'}prefix . '/')) {
                ${'$'}path = substr(${'$'}path, strlen(${'$'}prefix)) ?: '/';
                ${'$'}_SERVER['REQUEST_URI'] = ${'$'}path . (parse_url(${'$'}uri, PHP_URL_QUERY) !== null ? '?' . parse_url(${'$'}uri, PHP_URL_QUERY) : '');
            }
            ${'$'}public = realpath(getcwd());
            ${'$'}file = realpath(${'$'}public . '/' . rawurldecode(ltrim(${'$'}path, '/')));
            if (${'$'}file !== false && str_starts_with(${'$'}file, ${'$'}public . DIRECTORY_SEPARATOR) && is_file(${'$'}file)) {
                if (strtolower(pathinfo(${'$'}file, PATHINFO_EXTENSION)) === 'php') { require ${'$'}file; return true; }
                ${'$'}types = ['css'=>'text/css','js'=>'application/javascript','png'=>'image/png','jpg'=>'image/jpeg','jpeg'=>'image/jpeg','svg'=>'image/svg+xml','ico'=>'image/x-icon','woff'=>'font/woff','woff2'=>'font/woff2'];
                header('Content-Type: ' . (${'$'}types[strtolower(pathinfo(${'$'}file, PATHINFO_EXTENSION))] ?? 'application/octet-stream'));
                readfile(${'$'}file); return true;
            }
            require ${'$'}public . '/index.php';
        """.trimIndent()
        if(Files.exists(router)) require(Files.readString(router)==source) { "Local router was modified. Restore .awt/helper/local-router.php or use hostname path / before starting the server." }
        else Files.writeString(router,source,StandardOpenOption.CREATE_NEW)
        return Commands.server(c) + router.toAbsolutePath().toString()
    }
}
