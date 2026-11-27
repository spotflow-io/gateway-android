import { copyFileSync } from 'node:fs';

// pnpm runs prepack in the package directory and automatically includes its LICENSE.md.
copyFileSync(new URL('../../LICENSE.md', import.meta.url), 'LICENSE.md');
