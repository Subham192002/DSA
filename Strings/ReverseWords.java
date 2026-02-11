public class ReverseWords {

    public static String reverseWords(String s){

        String[] str = s.split(" ");
        StringBuilder res = new StringBuilder();

        for (int i =str.length-1;i>=0;i--){
            res.append(str[i]).append(" ");

        }
        return res.toString().trim();
    }

    public static void main(String[] args) {
        String s ="  Hello I am Subham ";
        System.out.println(reverseWords(s));
    }
}
