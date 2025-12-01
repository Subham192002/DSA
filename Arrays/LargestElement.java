//Find Largest element in array
public class LargestElement { //time complexity - O(n)

    public static int findLargest(int[] arr) {
        if (arr == null || arr.length == 0) {
            throw new IllegalArgumentException("Array cannot be empty");
        }

        int max = arr[0];

        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > max) {
                max = arr[i]; // update max when larger element is found
            }
        }
        return max;
    }

    public static void main(String[] args) {
        int[] array = {12, 5, 47, 19, 33};
        System.out.println("Largest element: " + findLargest(array));
    }
}
