import java.util.Arrays;

public class TwoSum {
    static class Solution {
        public int[] twoSum(int[] nums, int target) {
            int i =0,j=nums.length-1;
            while(i<j){
                if(nums[i]+nums[j]==target){
                    return new int[]{i,j};
                }
                else if(nums[i]+nums[j]>target){
                    j--;
                }
                else if(nums[i]+nums[j]<target){
                    i++;
                }
            }
            return new int[0];
        }
    }

    public static void main(String[] args) {
        Solution s = new Solution();
        LoggerUtil.LOG.info(Arrays.toString(s.twoSum(new int[]{3,8,10,21}, 31)));
        LoggerUtil.LOG.info(Arrays.toString(s.twoSum(new int[]{1, 2, 3, 4, 6}, 6))); // [1, 3]
        LoggerUtil.LOG.info(Arrays.toString(s.twoSum(new int[]{1, 2, 3, 4, 5}, 6))); // [0, 4]
        LoggerUtil.LOG.info(Arrays.toString(s.twoSum(new int[]{-5, -2, 0, 3, 8}, 1))); // [1, 3]
        LoggerUtil.LOG.info(Arrays.toString(s.twoSum(new int[]{1, 1, 2, 3, 4}, 2))); // [0, 1]
        LoggerUtil.LOG.info(Arrays.toString(s.twoSum(new int[]{1, 2, 3, 4, 5}, 20))); // []
        LoggerUtil.LOG.info(Arrays.toString(s.twoSum(new int[]{-10, -8, -5, -2, -1}, -11))); // [0, 4]

    }
}
