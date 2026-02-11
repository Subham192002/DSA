import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LongestSubString {
    private static final Logger log = Logger.getLogger(LongestSubString.class.getName());

    public static int longestSubString(String s){

        int left =0;
        int right =0;
        int maxLen=0;
        HashSet<Character> set =new HashSet<>();
        while (right<s.length()){
            if (!set.contains(s.charAt(right))) {
                set.add(s.charAt(right));
                right++;
                maxLen = Math.max(maxLen, right - left);
            }else{
                set.remove(s.charAt(left));
                left++;
            }
        }
        return maxLen;
    }

    public static void main(String[] args) {
        int num =longestSubString("qwertyq");
        log.log(Level.INFO, "Maximum length {0}", num);
    }
}
