# Example: input text case (generic)

> This is the raw input as a QA engineer might write it. Everything downstream in
> `examples/` is derived from this text. All names are generic — no real systems,
> URLs, topics or data.

---

**Case OT-101: Order creation is projected to the reporting database**

When a client creates an order through the Order Service API, the order must be accepted,
an `ORDER_CREATED` event must be published to the order events topic, and within half a
minute the order must appear in the reporting database with status `DONE`.

Steps (manual regression):

1. POST a new order with amount 100 to the Order Service (`/api/orders`),
   authenticated as the test user.
2. Check the response: HTTP 200, body contains `status = "ACCEPTED"` and a generated
   `orderId` like `ord-<digits>`.
3. Check that an event for this order appears in the order events topic with
   `status = "CREATED"`.
4. Check in the reporting DB (`reporting.orders` table) that the row for this `orderId`
   has `status = 'DONE'` within 30 seconds.
5. If the order amount is negative, the API must still return 200 but with
   `status = "REJECTED"` and no event must be published.

Notes: environment — the integration stand; test data must not collide between runs;
please clean up created rows.
