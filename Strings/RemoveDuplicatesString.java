import java.util.HashSet;

public class RemoveDuplicatesString {

    public static String removeDuplicates (String str){
        HashSet<Character> set= new HashSet<>();
        StringBuilder sb = new StringBuilder();
        for(int i=0;i<str.length();i++){
            char c = str.charAt(i);
            if(!set.contains(c)) {
                set.add(str.charAt(i));
                sb.append(str.charAt(i));
            }
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        String str = "subhammishra";
        System.out.println(removeDuplicates(str));
    }
}
