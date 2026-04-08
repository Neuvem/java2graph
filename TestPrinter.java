import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.Optional;

public class TestPrinter {
    public static void main(String[] args) {
        String code = "public class A {\n" +
                      "    public void foo() {\n" +
                      "        System.out.println(\"Hello World\");\n" +
                      "    }\n" +
                      "}\n";
        CompilationUnit cu = StaticJavaParser.parse(code);
        cu.findAll(MethodDeclaration.class).forEach(n -> {
            System.out.println("--- toString ---");
            System.out.println(n.toString());
            System.out.println("--- token range ---");
            System.out.println(n.getTokenRange().map(Object::toString).orElse("ERR"));
        });
    }
}
