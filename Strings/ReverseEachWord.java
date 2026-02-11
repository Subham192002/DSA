public class ReverseEachWord {
    public static String reverseEachWord(String s){
        String[] str = s.split(" ");
        StringBuilder res = new StringBuilder();

        for(String word:str) {
            for (int i = word.length() - 1; i >= 0; i--) {
                res.append(word.charAt(i));
            }
            res.append(" ");
        }
        return res.toString().trim().toUpperCase();
    }

    public static void main(String[] args) {
        String s ="  Hello I am Subham ";
        System.out.println(reverseEachWord(s));
    }
}
