package net.cumba.corej.editor.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.core.metadata.store.MetadataStoreWriter;
import net.cumba.corej.core.metadata.store.StoreProvenance;
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredCodelist;
import net.cumba.corej.core.metadata.store.StoredCtPackage;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredTerm;
import net.cumba.corej.core.metadata.store.StoredVariable;

/**
 * A miniature metadata store at the engine's format, written with the engine's own writer: SDTMIG
 * 3.4 with AE (Events), TS (Trial Design) and DM (Special Purpose) and their variables, the SDTM
 * 2.0 model with the Events class variables in {@code --} form, and one CT package holding the SEX
 * codelist. ⛔ Fixture data, never CDISC Library content from the pickle or the web API.
 */
public final class TestStore
{

    public static final String IG_KEY = "standards/sdtmig/3-4";

    public static final String MODEL_KEY = "models/sdtm/2-0";

    public static final String CT_PACKAGE = "sdtmct-2024-03-29";

    public static final String SEX_CODELIST = "C66731";

    private static Path written;

    private TestStore()
    {
    }


    /** The store file, written once per JVM under the test cwd. */
    public static synchronized Path file() throws IOException
    {
        if (written == null)
        {
            Path dir = Files.createTempDirectory(Path.of("").toAbsolutePath(), "test-store-");
            Path file = dir.resolve("metadata-store.zip");
            write(file);
            written = file;
        }
        return written;
    }


    public static void write(Path aFile) throws IOException
    {
        write(aFile, "TestStore");
    }


    /**
     * The same store with {@code aRef} as its provenance reference — tells two seeds apart
     * ({@code manifest().provenance().get(0).ref()}).
     */
    public static void write(Path aFile, String aRef) throws IOException
    {
        new MetadataStoreWriter().addProduct(ig()).addProduct(model()).addCtPackage(ct())
                .publishedCtPackages(List.of(CT_PACKAGE))
                .productCatalogue(List.of(IG_KEY, MODEL_KEY))
                .addProvenance(new StoreProvenance("fixture", aRef, "2026-10-06T00:00:00Z"))
                .write(aFile);
    }


    static StoredVariable var(String aName, String aLabel, String aCore, String aRole, String aType)
    {
        return StoredVariable.builder().name(aName).label(aLabel).core(aCore).role(aRole)
                .simpleDatatype(aType).build();
    }


    static StoredProduct ig()
    {
        StoredDataset ae = new StoredDataset("AE", "Adverse Events", "1", null, List.of(
                var("STUDYID", "Study Identifier", "Req", "Identifier", "Char"),
                var("DOMAIN", "Domain Abbreviation", "Req", "Identifier", "Char"),
                var("USUBJID", "Unique Subject Identifier", "Req", "Identifier", "Char"),
                var("AESEQ", "Sequence Number", "Req", "Identifier", "Num"),
                var("AETERM", "Reported Term for the Adverse Event", "Req", "Topic", "Char"),
                var("AEDECOD", "Dictionary-Derived Term", "Req", "Synonym Qualifier", "Char"),
                var("AESTDTC", "Start Date/Time of Adverse Event", "Exp", "Timing", "Char")),
                "One record per adverse event per subject", null);
        StoredDataset ts = new StoredDataset("TS", "Trial Summary", "1", null, List.of(
                var("STUDYID", "Study Identifier", "Req", "Identifier", "Char"),
                var("DOMAIN", "Domain Abbreviation", "Req", "Identifier", "Char"),
                var("TSSEQ", "Sequence Number", "Req", "Identifier", "Num"),
                var("TSPARMCD", "Trial Summary Parameter Short Name", "Req", "Topic", "Char"),
                var("TSPARM", "Trial Summary Parameter", "Req", "Synonym Qualifier", "Char"),
                var("TSVAL", "Parameter Value", "Exp", "Record Qualifier", "Char")),
                "One record per trial summary parameter value", null);
        StoredVariable sex = StoredVariable.builder().name("SEX").label("Sex").core("Req")
                .role("Record Qualifier").simpleDatatype("Char")
                .codelistSubmissionValues(List.of("SEX")).codelistIds(List.of(SEX_CODELIST))
                .build();
        StoredDataset dm = new StoredDataset("DM", "Demographics", "1", null,
                List.of(var("STUDYID", "Study Identifier", "Req", "Identifier", "Char"),
                        var("USUBJID", "Unique Subject Identifier", "Req", "Identifier", "Char"),
                        sex),
                "One record per subject", null);
        StoredClass events = new StoredClass("Events", "Events", "1", List.of(), List.of(ae), null,
                List.of(), List.of(), List.of());
        StoredClass trialDesign = new StoredClass("Trial Design", "Trial Design", "2", List.of(),
                List.of(ts), null, List.of(), List.of(), List.of());
        StoredClass special = new StoredClass("Special Purpose", "Special Purpose", "3", List.of(),
                List.of(dm), null, List.of(), List.of(), List.of());
        return StoredProduct.builder().key(IG_KEY).name("SDTMIG v3.4").label("SDTMIG v3.4")
                .version("3.4").modelHref("/mdr/sdtm/2-0")
                .classes(List.of(events, trialDesign, special)).datasets(List.of())
                .dataStructures(List.of()).domains(List.of()).build();
    }


    static StoredProduct model()
    {
        StoredClass events = new StoredClass("Events", "Events", "1",
                List.of(var("--TERM", "Reported Term for the Event", "Req", "Topic", "Char"),
                        var("--DECOD", "Dictionary-Derived Term", "Req", "Synonym Qualifier",
                                "Char"),
                        var("--STDTC", "Start Date/Time of Event", "Exp", "Timing", "Char")),
                List.of(), null, List.of(), List.of(), List.of());
        return StoredProduct.builder().key(MODEL_KEY).name("SDTM v2.0").label("SDTM v2.0")
                .version("2.0").classes(List.of(events)).datasets(List.of())
                .dataStructures(List.of()).domains(List.of()).build();
    }


    static StoredCtPackage ct()
    {
        StoredCodelist sex = new StoredCodelist("SEX", SEX_CODELIST, "Sex", "Sex",
                "The assemblage of physical properties or qualities by which male is"
                        + " distinguished from female.",
                List.of(), Boolean.FALSE,
                List.of(new StoredTerm("F", "C16576", "Female",
                        "A person who belongs to the sex" + " that normally produces ova.",
                        List.of()),
                        new StoredTerm("M", "C20197", "Male",
                                "A person who belongs to the sex"
                                        + " that normally produces sperm.",
                                List.of()),
                        new StoredTerm("U", "C17998", "Unknown", "Not known.", List.of())));
        return new StoredCtPackage(CT_PACKAGE, List.of(sex));
    }
}
