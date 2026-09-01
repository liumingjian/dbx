/**
 * Self-hosted IBM Plex (ADR-0014).
 *
 * The two `-default.css` files carry exactly Light (300), Regular (400) and SemiBold (600)
 * in normal style, served from the official split subsets under each package's
 * `fonts/split/woff2/hinted/`. That is the whole of what DBX loads — `-all.css` would pull
 * eight weights, and Google Fonts is not an option because it does not serve
 * IBM Plex Sans SC.
 */
import '@ibm/plex-sans/css/ibm-plex-sans-default.css';
import '@ibm/plex-sans-sc/css/ibm-plex-sans-sc-default.css';
