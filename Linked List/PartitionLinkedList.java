public class PartitionLinkedList {

    public static ListNode partition(ListNode head, int x) {

        if (head == null) return null;

        ListNode lessHead = null, lessTail = null;
        ListNode greaterHead = null, greaterTail = null;

        ListNode curr = head;

        while (curr != null) {
            ListNode next = curr.next;
            curr.next = null;

            if (curr.val < x) {
                if (lessHead == null) {
                    lessHead = lessTail = curr;
                } else {
                    lessTail.next = curr;
                    lessTail = curr;
                }
            } else {
                if (greaterHead == null) {
                    greaterHead = greaterTail = curr;
                } else {
                    greaterTail.next = curr;
                    greaterTail = curr;
                }
            }

            curr = next;
        }

        if (lessHead == null) {
            head = greaterHead;
        } else {
            lessTail.next = greaterHead;
            head = lessHead;
        }

        return head;
    }


    public static void main(String[] args) {

        ListNode head = new ListNode(1,
                new ListNode(4,
                        new ListNode(3,
                                new ListNode(2,
                                        new ListNode(5,
                                                new ListNode(2))))));
        head = partition(head, 3);
        while (head != null) {
            System.out.print(head.val + " ");
            head = head.next;
        }
    }
}
