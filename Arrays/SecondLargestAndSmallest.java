/*
Question:
Given an array of integers, find the second largest and the second smallest
elements in the array. Return both values in an array of size 2.
Time Complexity: O(n)
*/

import java.util.Arrays;
import java.util.logging.Logger;

public class SecondLargestAndSmallest {

    static Logger log = Logger.getLogger(SecondLargestAndSmallest.class.getName());

    public static int[] findSecondLargestAndSmallest(int[] arr) {
        if (arr == null || arr.length < 2) {
            throw new IllegalArgumentException("Array must contain at least two elements");
        }

        int largest = Integer.MIN_VALUE;
        int secondLargest = Integer.MIN_VALUE;

        int smallest = Integer.MAX_VALUE;
        int secondSmallest = Integer.MAX_VALUE;

        for (int num : arr) {
            if (num > largest) {
                secondLargest = largest;
                largest = num;
            } else if (num > secondLargest && num < largest) {
                secondLargest = num;
            }

            if (num < smallest) {
                secondSmallest = smallest;
                smallest = num;
            } else if (num < secondSmallest && num > smallest) {
                secondSmallest = num;
            }
        }

        if (secondLargest == Integer.MIN_VALUE || secondSmallest == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Second largest or second smallest does not exist (array may have duplicates).");
        }

        return new int[]{secondLargest, secondSmallest};
    }

    public static void main(String[] args) {
        int[] array = {12, 35, 1, 10, 34, 2};
        int[] result = findSecondLargestAndSmallest(array);
        log.info(Arrays.toString(result));
    }
}
