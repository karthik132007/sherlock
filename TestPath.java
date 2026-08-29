import java.nio.file.Paths;

public class TestPath {
    public static void main(String[] args) {
        String userHome = System.getProperty("user.home");
        String path = Paths.get(userHome, "Documents", "Sherlock").toString();
        System.out.println(path);
    }
}
