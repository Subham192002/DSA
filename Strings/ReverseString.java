import java.util.HashSet;

public class ReverseString {

    public static void main(String[] args) {
        String s = "programming";
        StringBuilder result = new StringBuilder();

        for(int i =s.length()-1;i>=0;i--) {
            result.append(String.valueOf(s.charAt(i)));
        }

        System.out.println(result.toString());
    }
}
