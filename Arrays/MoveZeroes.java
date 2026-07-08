
//Given an integer array nums, move all 0s to the end while maintaining the relative order of the non-zero elements.
//
//Example
//Input: [0,1,0,3,12]
//
//Output: [1,3,12,0,0]
public class MoveZeroes {

    public static void moveZeroes(int[] nums) {

        int j = 0; // position for next non-zero element

        for (int i = 0; i < nums.length; i++) {

            if (nums[i] != 0) {

                int temp = nums[i];
                nums[i] = nums[j];
                nums[j] = temp;

                j++;
            }
        }
    }

    public static void main(String[] args) {

        int[] nums = {0, 1, 0, 3, 12};

        moveZeroes(nums);

        for (int num : nums) {
            System.out.print(num + " ");
        }
    }
}
