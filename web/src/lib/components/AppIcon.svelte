<script lang="ts">
  import { appAsset } from '#lib/paths.js';
  import { preferences } from '#lib/preferences.svelte.js';
</script>

<img
  class="app-icon"
  src={appAsset(preferences.dark ? 'app-icon-dark.png' : 'app-icon.png')}
  width="28"
  height="28"
  alt=""
/>
