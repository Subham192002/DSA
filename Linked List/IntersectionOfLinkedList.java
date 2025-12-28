public class IntersectionOfLinkedList {

    static class ListNode {
        int val;
        ListNode next;

        ListNode(int x) {
            val = x;
            next = null;
        }
    }

    static class Solution {
        public ListNode getIntersectionNode(ListNode headA, ListNode headB) {
            if (headA == null || headB == null) return null;

            ListNode pA = headA;
            ListNode pB = headB;

            while (pA != pB) {
                if (pA == null) {
                    pA = headB;
                } else {
                    pA = pA.next;
                }
                if (pB == null) {
                    pB = headA;
                } else {
                    pB = pB.next;
                }
            }
            return pA;
        }
    }

    // ✅ MAIN METHOD FOR TESTING
    public static void main(String[] args) {
        // Create intersection
        ListNode intersect = new ListNode(8);
        intersect.next = new ListNode(10);

        // List A: 3 → 7 → 8 → 10
        ListNode headA = new ListNode(3);
        headA.next = new ListNode(7);
        headA.next.next = intersect;

        // List B: 99 → 1 → 8 → 10
        ListNode headB = new ListNode(99);
        headB.next = new ListNode(1);
        headB.next.next = intersect;

        Solution sol = new Solution();
        ListNode result = sol.getIntersectionNode(headA, headB);

        if (result != null) {
            System.out.println("Intersection at node value: " + result.val);
        } else {
            System.out.println("No intersection");
        }
    }
}
