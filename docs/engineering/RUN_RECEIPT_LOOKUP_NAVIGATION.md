# Reach preparation receipts after Run state changes

Run details now provide a permanent, explicitly historical lookup link to the existing preparation/receipt page. Previously only BLOCKED state exposed the condition-check link; after preparation changes Run to CREATED, users returning from another page could not discover the receipt form. The new lookup link is available with the authorized Run projection, including CREATED and COMPLETED. The existing BLOCKED-only preparation condition link remains unchanged.

Navigation is read-only and does not infer preparation eligibility or Start authority. The destination independently revalidates current ADMIN and receipt scope; receipt reads still require the original key. Disabled recovery or absent receipts do not imply a failed command. No backend, schema or permission changes.

Browser cases across three viewports verify unique lookup links and exact scope for CREATED/COMPLETED, absence of the BLOCKED-only action in these states, destination lookup form and zero mutations. Retained preparation/history cases remain. Native lookup acceptance on PR148 already passed Vault and Quality; Docker was pending at first observation.
