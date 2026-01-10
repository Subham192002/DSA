import java.util.HashSet;

public class RemoveDuplicatesString {

    public static void main(String[] args) {
        String s = "programming";
        StringBuilder result = new StringBuilder();
        HashSet<Character> set = new HashSet<>();

        for(char c : s.toCharArray()) {
            if(!set.contains(c)) {
                set.add(c);
                result.append(c);
            }
        }

        System.out.println(result.toString());
    }
}
