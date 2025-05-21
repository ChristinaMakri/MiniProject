package org.example.miniproject;

import be.ugent.rml.cli.Main;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.http.HTTPRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.BindingSet;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.stream.Collectors;

public class MiniProjectMain {

    private static final String SERVER_URL = "http://localhost:7200";
    private static final String REPO_ID    = "MiniProject";

    // Εάν έχεις κοινά PREFIXES για όλα τα queries, ορίζεις εδώ:
    private static final String COMMON_PREFIXES =
            "PREFIX jm: <http://www.semanticweb.org/cmakri/ontologies/2025/4/JobMatching-Migration#>\n" +
                    "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>";

    public static void main(String[] args) throws Exception {
        // 1) Extract resources από το classpath σε target/
        String[] resources = {
                "mapping.ttl",
                "hrm_final_dataset.csv",
                "JobMatching-Migration.owl",
                "queries.sparql"
        };
        for (String r : resources) {
            extractResource(r);
        }

        // 2) Εκτέλεση RML πάνω στο target/mapping.ttl
        Main.main(new String[]{
                "-m", "target/mapping.ttl",
                "-o", "target/transformedData.ttl",
                "-s", "turtle"
        });

        // 3) Σύνδεση με GraphDB
        HTTPRepository repo = new HTTPRepository(SERVER_URL, REPO_ID);
        repo.init();

        try (RepositoryConnection conn = repo.getConnection()) {
            // 4) Φόρτωση OWL
            conn.add(new File("target/JobMatching-Migration.owl"),
                    new File("target/JobMatching-Migration.owl").toURI().toString(),
                    RDFFormat.RDFXML);

            // 5) Φόρτωση TTL από το RML
            conn.add(new File("target/transformedData.ttl"),
                    new File("target/transformedData.ttl").toURI().toString(),
                    RDFFormat.TURTLE);

            // 6) Διαβάζω και τρέχω κάθε SPARQL query
            String all = Files.readString(Path.of("target/queries.sparql")).trim();

            // 6a) Ξεχωρίζω την αρχική ενότητα με τα PREFIXES
            String[] parts = all.split("\\r?\\n", -1);
            StringBuilder sbPrefixes = new StringBuilder();
            int i = 0;
            while (i < parts.length && parts[i].trim().startsWith("PREFIX")) {
                sbPrefixes.append(parts[i]).append("\n");
                i++;
            }
            String commonPrefixes = sbPrefixes.toString().trim();

            // 6b) Τα υπόλοιπα lines → ένα μεγάλο string
            String rest = String.join("\n", Arrays.copyOfRange(parts, i, parts.length)).trim();

            // 6c) Σπάμε σε blocks με βάση δύο κενές γραμμές
            String[] queries = rest.split("\\r?\\n\\s*\\r?\\n");

            for (String q : queries) {
                // αδειάζουμε σχόλια
                String cleaned = Arrays.stream(q.split("\\r?\\n"))
                        .filter(line -> !line.trim().startsWith("#"))
                        .collect(Collectors.joining("\n"))
                        .trim();
                if (cleaned.isEmpty()) continue;

                // μόνο εάν περιέχει SELECT/ASK/…
                if (!(cleaned.matches("(?s).*\\b(SELECT|ASK|CONSTRUCT|DESCRIBE)\\b.*"))) {
                    continue;
                }

                // τελικό query: κοινά prefixes + block
                String finalQuery = commonPrefixes + "\n" + cleaned;
                System.out.println("=== Query ===\n" + finalQuery);

                TupleQuery tq = conn.prepareTupleQuery(QueryLanguage.SPARQL, finalQuery);
                try (TupleQueryResult res = tq.evaluate()) {
                    while (res.hasNext()) {
                        System.out.println(res.next());
                    }
                }
            }

        } finally {
            repo.shutDown();
        }
    }

    private static void extractResource(String name) throws IOException {
        URL url = MiniProjectMain.class.getResource("/" + name);
        if (url == null) {
            throw new FileNotFoundException("Resource not found: " + name);
        }
        try (InputStream in = MiniProjectMain.class.getResourceAsStream("/" + name)) {
            Files.copy(in, Path.of("target", name), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
