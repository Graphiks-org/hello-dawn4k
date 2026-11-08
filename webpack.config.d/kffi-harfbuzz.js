// HarfBuzz's kffi glue probes for Node ("module" builtin) before choosing its
// browser path; webpack must not try to bundle that probe for the web target.
config.resolve = config.resolve || {};
config.resolve.fallback = Object.assign({}, config.resolve.fallback, { module: false });
