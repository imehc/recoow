<script lang="ts">
  import { preferences } from '#lib/preferences.svelte.js';
</script>

<img
  class="app-icon"
  src={preferences.dark ? '/app-icon-dark.png' : '/app-icon.png'}
  width="28"
  height="28"
  alt=""
/>
