import com.ladybugdb.Connection;
import com.ladybugdb.Database;
import com.ladybugdb.QueryResult;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;

public class TestLadybug {
    public static void main(String[] args) throws Exception {
        Path dbDir = Files.createTempDirectory("testdb");
        Path dbPath = dbDir.resolve("db.lbug");
        Database db = new Database(dbPath.toString());
        Connection conn = new Connection(db);
        
        conn.query("CREATE NODE TABLE Class(id STRING, fqn STRING, PRIMARY KEY (id))").close();
        
        Path csv = Files.createTempFile("test", ".csv");
        try (FileWriter out = new FileWriter(csv.toFile())) {
            out.write("id1,fqn1\n");
            out.write("id2,fqn2\n");
        }
        
        try {
            System.out.println("Executing COPY from " + csv);
            QueryResult res = conn.query("COPY Class FROM '" + csv.toAbsolutePath().toString() + "'");
            System.out.println("Success: " + res.isSuccess());
            if (!res.isSuccess()) {
                System.out.println("Error: " + res.getErrorMessage());
            }
            res.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        conn.close();
        db.close();
    }
}
