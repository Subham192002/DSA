// Remove duplicates from sorted array
public class RemoveDuplicates {

    public static int removeDuplicates(int[] arr) {
        if (arr.length == 0) return 0;

        int j = 1;  // pointer for unique elements

        for (int i = 1; i < arr.length; i++) {
            if (arr[i] != arr[i - 1]) {
                arr[j] = arr[i];
                j++;
            }
        }
        return j;
    }

    public static void main(String[] args) {

        int[] arr = {-2, 2, 4, 4, 4, 4, 5, 5};
        int k = removeDuplicates(arr);

        System.out.println("Unique count = " + k);

        System.out.print("Resulting array = [");
        for (int i = 0; i < arr.length; i++) {
            if (i < arr.length - 1)
                System.out.print(arr[i] + ", ");
            else
                System.out.print(arr[i]);
        }
        System.out.println("]");
    }
}
