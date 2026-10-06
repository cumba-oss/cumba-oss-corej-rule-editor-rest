package net.cumba.corej.editor.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the chosen brand as {@code /brand/**} when the SPA is bundled into this jar
 * ({@code -Pbundle-web}; PLAN-web-branding-and-theme §5.5). The SPA always loads
 * {@code /brand/brand.css}; the brand is {@code editor.brand.name} ({@code EDITOR_BRAND_NAME},
 * default {@code neutral}), looked up first under {@code editor.brand.directory} (the launcher's
 * {@code .conf} points it at {@code config/brands}), then among the brands packaged with the SPA
 * ({@code classpath:/static/brands/<name>/}). An unknown or invalid name <b>fails start-up</b>,
 * naming the brands available in both places — a typo must not silently ship the default look.
 *
 * <p>
 * An API-only build (no {@code classpath:/static/index.html}) registers no mapping and validates
 * nothing: one INFO line.
 * </p>
 *
 * <p>
 * {@code final} and {@code proxyBeanMethods = false} (no {@code @Bean} methods, so no CGLIB
 * subclass is needed): the constructor throws on an unknown brand, and SpotBugs'
 * CT_CONSTRUCTOR_THROW wants a class that throws from its constructor to be unsubclassable.
 * </p>
 */
@Configuration(proxyBeanMethods = false)
public final class BrandResourceConfig implements WebMvcConfigurer
{

    /** The name rule every brand server checks before the name touches a path. */
    static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,39}$");

    static final String BUNDLED_INDEX = "classpath:/static/index.html";

    static final String PACKAGED = "classpath:/static/brands/";

    private static final Logger LOG = LoggerFactory.getLogger(BrandResourceConfig.class);

    private final @Nullable String location;

    @Autowired
    public BrandResourceConfig(EditorProperties aProperties, ResourceLoader aLoader)
    {
        this(aProperties.getBrand(), aLoader);
    }


    BrandResourceConfig(EditorProperties.Brand aBrand, ResourceLoader aLoader)
    {
        location = resolve(aBrand, aLoader);
        if (location == null)
        {
            LOG.info("no bundled SPA (API-only build): /brand/ is not served");
        }
        else
        {
            LOG.info("brand '{}' served as /brand/ from {}", aBrand.getName(), location);
        }
    }


    /**
     * The resource location {@code /brand/**} is served from, or {@code null} for an API-only
     * build.
     *
     * @throws IllegalStateException
     *             on an invalid or unknown brand name, naming the available brands
     */
    static @Nullable String resolve(EditorProperties.Brand aBrand, ResourceLoader aLoader)
    {
        if (!aLoader.getResource(BUNDLED_INDEX).exists())
        {
            return null;
        }
        String name = aBrand.getName().strip();
        Path directory = directory(aBrand);
        if (!NAME.matcher(name).matches())
        {
            throw new IllegalStateException(
                    "editor.brand.name \"" + name + "\" is not a brand name (" + NAME
                            + "); available: " + available(directory, aLoader));
        }
        if (directory != null && Files.isRegularFile(directory.resolve(name).resolve("brand.css")))
        {
            return directory.resolve(name).toUri().toString();
        }
        if (aLoader.getResource(PACKAGED + name + "/brand.css").exists())
        {
            return PACKAGED + name + "/";
        }
        throw new IllegalStateException("editor.brand.name \"" + name + "\" has no brand.css"
                + (directory == null ? "" : " under " + directory)
                + " and is not packaged with the SPA; available: " + available(directory, aLoader));
    }


    private static @Nullable Path directory(EditorProperties.Brand aBrand)
    {
        String configured = aBrand.getDirectory();
        if (configured == null || configured.isBlank())
        {
            return null;
        }
        return Path.of(configured.strip()).toAbsolutePath();
    }


    /**
     * The brands under the external directory and among the packaged ones, sorted, deduplicated.
     */
    static List<String> available(@Nullable Path aDirectory, ResourceLoader aLoader)
    {
        TreeSet<String> names = new TreeSet<>();
        if (aDirectory != null && Files.isDirectory(aDirectory))
        {
            try (Stream<Path> children = Files.list(aDirectory))
            {
                children.filter(p -> Files.isRegularFile(p.resolve("brand.css")))
                        .forEach(p -> names.add(p.getFileName().toString()));
            }
            catch (IOException e)
            {
                LOG.warn("cannot list the brand directory {}: {}", aDirectory, e.toString());
            }
        }
        try
        {
            Resource[] packaged = new PathMatchingResourcePatternResolver(aLoader)
                    .getResources("classpath*:static/brands/*/brand.css");
            for (Resource css : packaged)
            {
                String url = css.getURL().toString();
                int end = url.lastIndexOf("/brand.css");
                int start = url.lastIndexOf('/', end - 1);
                if (end > 0 && start >= 0)
                {
                    names.add(url.substring(start + 1, end));
                }
            }
        }
        catch (IOException e)
        {
            LOG.warn("cannot list the packaged brands: {}", e.toString());
        }
        return new ArrayList<>(names);
    }


    /** Where {@code /brand/**} is served from; empty for an API-only build. */
    public Optional<String> location()
    {
        return Optional.ofNullable(location);
    }


    @Override
    public void addResourceHandlers(ResourceHandlerRegistry aRegistry)
    {
        if (location == null)
        {
            return;
        }
        // no-cache: an edited brand shows on reload, as the nginx image serves it
        aRegistry.addResourceHandler("/brand/**").addResourceLocations(location)
                .setCacheControl(CacheControl.noCache());
    }
}
