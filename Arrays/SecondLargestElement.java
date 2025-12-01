// Second Largest element in an array

public class SecondLargestElement {

    public static  int findSecondLargest(int[] arr){
        if (arr == null || arr.length < 2) {
            throw new IllegalArgumentException("Array must contain at least two elements");
        }

        int largest =arr[0];
        int secondLargest =Integer.MIN_VALUE;

//        For each loop
//        for (int num : arr) {
//            if (num > largest) {
//                secondLargest = largest; // update second largest
//                largest = num;           // update largest
//            } else if (num > secondLargest && num < largest) {
//                secondLargest = num;
//            }
//        }

        for (int i = 0; i < arr.length; i++){
            if (arr[i]>largest){
                secondLargest=largest;
                largest=arr[i];
            } else if (arr[i]>secondLargest && arr[i]<largest) {
                secondLargest=arr[i];
            }
        }

        if (secondLargest == Integer.MIN_VALUE) {
            throw new IllegalArgumentException("No second largest element (all values may be equal)");
        }

        return secondLargest;
    }

    public static void main(String[] args) {
        int[] array = {};
        System.out.println("Second Largest Element: " + findSecondLargest(array));
    }
}
