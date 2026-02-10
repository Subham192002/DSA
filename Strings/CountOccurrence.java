import java.util.LinkedHashMap;
import java.util.Map;

public class CountOccurrence {

    public static Map<Character, Integer> countOccurence(String str){
        String s = str.toLowerCase();
        LinkedHashMap<Character, Integer> map = new LinkedHashMap<>(); // Linked hash map for maintaing order ,, normal hasmap for no-order ,, Tree map for alphabetical order
        for(int i=0;i<s.length();i++){
            char c = s.charAt(i);
            map.put(c, map.getOrDefault(c, 0) + 1);
        }
        return map;
    }

    public static void main(String[] args) {
        String str = "SubhamMishra";
        System.out.println(countOccurence(str));
    }
}
