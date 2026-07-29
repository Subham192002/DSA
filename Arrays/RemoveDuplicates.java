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

        LoggerUtil.LOG.info("Unique count =" + k);

        LoggerUtil.LOG.info("Resulting array = [");
        for (int i = 0; i < arr.length; i++) {
            LoggerUtil.LOG.info("arr[" + i + "] = " + arr[i]);
        }
    }
}
