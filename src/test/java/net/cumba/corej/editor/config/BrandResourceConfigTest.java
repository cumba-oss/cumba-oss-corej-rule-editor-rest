package net.cumba.corej.editor.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.cumba.corej.editor.HomeStaysCleanExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

/**
 * The brand resolution of {@link BrandResourceConfig} (PLAN-web-branding-and-theme §5.5, phase 3):
 * packaged brand, external directory outranking it, unknown and invalid names failing with the
 * available names, an API-only build mapping nothing — and the environment variable through the
 * real binding path: {@code EDITOR_BRAND_NAME} binds, {@code EDITOR_BRAND} does not (review H1:
 * with no placeholder in {@code application.yaml}, relaxed binding maps it to {@code editor.brand}
 * itself; with the shipped {@code application.yaml} supplying {@code name} and {@code directory}
 * the binder silently drops the string — measured in review round 1 (HIGH-1): without a guard the
 * service started and served {@code neutral}. {@link BrandResourceConfig} refuses the stray value,
 * so the typo is loud).
 *
 * <p>
 * The test classpath carries a synthetic bundled SPA: {@code static/index.html} and
 * {@code static/brands/{neutral,p300}/brand.css} under {@code src/test/resources}.
 * </p>
 */
@ExtendWith(HomeStaysCleanExtension.class)
class BrandResourceConfigTest
{

    @TempDir
    Path tmp;

    /** A loader whose classpath holds no bundled SPA at all. */
    private ResourceLoader apiOnlyLoader() throws IOException
    {
        Path empty = Files.createDirectories(tmp.resolve("empty"));
        return new DefaultResourceLoader(new URLClassLoader(new URL[]
        {
                empty.toUri().toURL()
        }, ClassLoader.getPlatformClassLoader()));
    }


    private static EditorProperties.Brand brand(String aName, String aDirectory)
    {
        EditorProperties.Brand brand = new EditorProperties.Brand();
        brand.setName(aName);
        brand.setDirectory(aDirectory);
        return brand;
    }


    private Path external(String aName) throws IOException
    {
        Path dir = Files.createDirectories(tmp.resolve("brands").resolve(aName));
        Files.writeString(dir.resolve("brand.css"), "/* external " + aName + " */",
                StandardCharsets.UTF_8);
        return dir;
    }


    @Test
    void packagedBrandIsServedFromTheClasspath()
    {
        ResourceLoader loader = new DefaultResourceLoader();
        assertEquals("classpath:/static/brands/neutral/",
                BrandResourceConfig.resolve(brand("neutral", null), loader));
        assertEquals("classpath:/static/brands/p300/",
                BrandResourceConfig.resolve(brand(" p300 ", ""), loader));
        // a directory without that brand falls through to the packaged copy
        assertEquals("classpath:/static/brands/p300/", BrandResourceConfig
                .resolve(brand("p300", tmp.resolve("nowhere").toString()), loader));
    }


    @Test
    void externalDirectoryOutranksThePackagedBrand() throws IOException
    {
        Path p300 = external("p300");
        external("acme");
        ResourceLoader loader = new DefaultResourceLoader();
        String location = BrandResourceConfig
                .resolve(brand("p300", tmp.resolve("brands").toString()), loader);
        assertNotNull(location);
        assertEquals(p300.toUri().toString(), location);
        assertTrue(location.startsWith("file:") && location.endsWith("/"), location);
        assertEquals(tmp.resolve("brands/acme").toUri().toString(), BrandResourceConfig
                .resolve(brand("acme", tmp.resolve("brands").toString()), loader));
    }


    @Test
    void unknownNameFailsNamingTheAvailableBrandsOfBothPlaces() throws IOException
    {
        external("acme");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BrandResourceConfig.resolve(brand("nope", tmp.resolve("brands").toString()),
                        new DefaultResourceLoader()));
        assertTrue(e.getMessage().contains("editor.brand.name \"nope\" has no brand.css"),
                e.getMessage());
        assertTrue(e.getMessage().endsWith("available: [acme, neutral, p300]"), e.getMessage());
        assertEquals(List.of("neutral", "p300"),
                BrandResourceConfig.available(null, new DefaultResourceLoader()));
    }


    @Test
    void invalidNameFailsBeforeAnyPathIsBuilt()
    {
        for (String bad : List.of("../x", "A", "p300/x", "-x", "", "x".repeat(41)))
        {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> BrandResourceConfig.resolve(brand(bad, null),
                            new DefaultResourceLoader()),
                    bad);
            assertTrue(e.getMessage().contains("is not a brand name"), e.getMessage());
            assertTrue(e.getMessage().contains("available: [neutral, p300]"), e.getMessage());
        }
    }


    @Test
    void apiOnlyBuildMapsNothingAndValidatesNothing() throws IOException
    {
        ResourceLoader loader = apiOnlyLoader();
        assertNull(BrandResourceConfig.resolve(brand("nope", null), loader));
        assertNull(BrandResourceConfig.resolve(brand("../x", null), loader));
        assertTrue(new BrandResourceConfig(brand("nope", null), loader, new StandardEnvironment())
                .location().isEmpty());
        // the stray object value is refused even in an API-only build
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new BrandResourceConfig(brand("neutral", null), loader,
                        environmentWith("EDITOR_BRAND", "p300")));
        assertTrue(e.getMessage().contains("EDITOR_BRAND_NAME"), e.getMessage());
    }


    private static StandardEnvironment environmentWith(String aName, String aValue)
    {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "test-systemEnvironment", Map.of(aName, aValue)));
        return environment;
    }

    @Configuration
    @EnableConfigurationProperties(EditorProperties.class)
    static class Properties
    {
    }

    private static ApplicationContextRunner runner(Map<String, Object> aEnvironment)
    {
        return new ApplicationContextRunner()
                .withUserConfiguration(Properties.class, BrandResourceConfig.class)
                // the SHIPPED application.yaml, as in the real app: it supplies editor.brand.name
                // and editor.brand.directory, which is what makes a stray EDITOR_BRAND bind
                // silently (review round 1 HIGH-1) unless the config refuses it
                .withInitializer(new ConfigDataApplicationContextInitializer())
                // the NAME must end in "systemEnvironment" for Spring to apply the relaxed
                // (upper-case, underscore) mapping to the source; first, as the real environment
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment",
                                aEnvironment)));
    }


    @Test
    void editorBrandNameBindsThroughTheEnvironment()
    {
        runner(Map.of("EDITOR_BRAND_NAME", "p300")).run(ctx ->
        {
            assertNull(ctx.getStartupFailure());
            assertEquals("p300", ctx.getBean(EditorProperties.class).getBrand().getName());
            assertEquals("classpath:/static/brands/p300/",
                    ctx.getBean(BrandResourceConfig.class).location().orElseThrow());
        });
    }


    @Test
    void editorBrandAloneNeverSelectsABrand()
    {
        // review H1 / round-1 HIGH-1: EDITOR_BRAND maps to editor.brand — the object. With the
        // shipped application.yaml supplying name and directory, the binder swallows the string
        // (bindObject catches the conversion failure and binds the nested object instead), so
        // without a guard the real app silently served neutral. BrandResourceConfig refuses it.
        runner(Map.of("EDITOR_BRAND", "p300")).run(ctx ->
        {
            Throwable failure = ctx.getStartupFailure();
            assertNotNull(failure, "EDITOR_BRAND must not start the service as if nothing was set");
            Throwable cause = failure;
            while (cause.getCause() != null && !(cause instanceof IllegalStateException
                    && cause.getMessage().contains("EDITOR_BRAND_NAME")))
            {
                cause = cause.getCause();
            }
            assertTrue(cause.getMessage().contains("EDITOR_BRAND_NAME"), cause.getMessage());
            assertTrue(cause.getMessage().contains("\"p300\""), cause.getMessage());
        });
    }


    @Test
    void unknownNameThroughTheEnvironmentFailsTheContext()
    {
        runner(Map.of("EDITOR_BRAND_NAME", "nope")).run(ctx ->
        {
            Throwable failure = ctx.getStartupFailure();
            assertNotNull(failure, "the context must not start on an unknown brand");
            Throwable cause = failure;
            while (cause.getCause() != null && !(cause instanceof IllegalStateException
                    && cause.getMessage().contains("editor.brand.name")))
            {
                cause = cause.getCause();
            }
            assertTrue(cause.getMessage().contains("\"nope\""), cause.getMessage());
            assertTrue(cause.getMessage().contains("available: [neutral, p300]"),
                    cause.getMessage());
        });
    }
}
