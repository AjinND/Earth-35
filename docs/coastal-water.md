# Beach and shoreline water

The coastal water adds rolling shore waves, broken foam, receding wash and wet-sand edges around the island. Moonwash Cove has gentler breakers than exposed shores. Small, irregular ripples catch the existing world lighting, and rain increases surface movement. Reduced-motion preferences freeze decorative wave animation.

The surf follows the outside sand edge in a narrow mesh and leaves the Silverstream river mouth open. The existing dry-land geometry and navigation stay authoritative. Wave motion runs in the material shader without uploading geometry every frame; the surf geometry is shared between views.

The frontend is deployed to the saved city at <http://localhost:3000> on 15 October 2026. The production build and TypeScript checks passed, along with 11 focused checks for coastal rendering, geometry, dry ground and the river mouth. The desktop and mobile surf checks also passed against the production image, including rain, reduced motion and first-person exploration. See the [deployment record](../artifacts/coastal-water-20261015/deployment/deployment.json) for backup, preservation and live-browser verification details.
