# Case: unknown showcase code

Requesting the format of a showcase code that does not exist must NOT return 200.
The stand answers 404 for unknown codes.

Verify that the service rejects code `NO-SUCH-CODE` with HTTP 404.

Environment: IFT.
