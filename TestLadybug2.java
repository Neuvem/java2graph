import com.ladybugdb.Connection;
import com.ladybugdb.Database;
import com.ladybugdb.QueryResult;
import java.nio.file.Files;
import java.nio.file.Path;

public class TestLadybug2 {
    public static void main(String[] args) throws Exception {
        Database db = new Database("./decypher.db");
        Connection conn = new Connection(db);
        
        try {
            QueryResult res = conn.query("MATCH (c:Class) RETURN count(c)");
            if (res.isSuccess() && res.hasNext()) {
                System.out.println("Class count: " + res.getNext().getValue(0));
            }
            res.close();
            
            res = conn.query("MATCH (m:Method) RETURN count(m)");
            if (res.isSuccess() && res.hasNext()) {
                System.out.println("Method count: " + res.getNext().getValue(0));
            }
            res.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        conn.close();
        db.close();
    }
}
