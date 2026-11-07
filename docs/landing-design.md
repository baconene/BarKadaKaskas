# Landing page refresh

## Design review

Source: user-provided landing-page screenshot, November 8, 2026.

1. Hero: the original text-only layout left a large empty area. Added a travel
   image and a stronger primary action while retaining the green brand palette.
2. Product explanation: kept planning, expense splitting and repayment tracking,
   with an explicitly labeled sample trip to explain the experience.
3. Downloads: long installation instructions dominated the original page.
   Moved them into keyboard-accessible native disclosure sections. Kept Android,
   iPhone IPA and release links, with browser access as the simplest starting path.

No emoji, fabricated testimonials, user counts or customer endorsements are used.
The sample trip is illustrative rather than a live account or customer record.

## Image provenance

`public/images/landing/barkada-coast.jpg` was generated using the built-in Image
Gen tool for this page, then JPEG-encoded at quality 85 for delivery (about 491 KB).
It is illustrative brand imagery, not a photograph of actual customers.

Generation prompt:

> Create a photorealistic editorial travel lifestyle photograph for the BarKadaKaskas travel-with-friends website hero. Landscape 3:2 composition, 1536x1024 if available. A small group of four Filipino adult friends in their late twenties seen mostly from behind or in natural three-quarter profile, candidly walking along a sunlit tropical island beach path toward calm turquoise sea in the Philippines, limestone coastline in distance, a simple wooden outrigger boat near water, coconut palm shade edges. One wears a rust-orange shirt, others relaxed cream and muted olive clothes, daypacks and a straw beach tote. Natural proportions, authentic travel documentary photography, unposed, people small in scene occupying lower middle third, lots of scenic coast, warm late afternoon light, subtle analog grain, restrained organic colors, sophisticated travel magazine aesthetic. No text, no watermark, no logos, no graphic overlays, no illustrated elements, no emoji. This is an illustrative brand asset, not a testimonial or a real customer photo.

## Verification

- Vue TypeScript check and Vite production build passed.
- Existing landing tests passed (2 tests, 26 assertions).
- Desktop and 390 × 844 responsive views inspected in the in-app browser.
- Mobile overflow check passed; hero image loaded successfully.
- Plan a trip opened registration; iPhone disclosure exposed the configured IPA
  link. No installer was downloaded or run during verification.
- Includes alt text, visible focus styles, a skip link and reduced-motion rules.
  This is not a full assistive-technology accessibility audit.
