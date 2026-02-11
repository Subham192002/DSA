public class PalindromeCheck {

    public static boolean palindromeCheck(String s){

        StringBuilder result = new StringBuilder();

        for(int i =s.length()-1;i>=0;i--) {
            result.append(String.valueOf(s.charAt(i)));
        }
        return result.toString().equalsIgnoreCase(s);
    }

    public static void main(String[] args) {
        String s = "man";
        System.out.println(palindromeCheck(s));
    }
}
