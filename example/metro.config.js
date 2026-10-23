const path = require('node:path');
const { getDefaultConfig } = require('expo/metro-config');

const config = getDefaultConfig(__dirname);
const packageRoot = path.resolve(__dirname, '..');

// tsconfig.json points the package's name at ../src. The folder above has its own node_modules
// for its type checks and tests, and a second copy of react or react-native in the bundle breaks
// the app, so every import resolves from this folder's node_modules.
config.watchFolders = [packageRoot];
config.resolver.nodeModulesPaths = [path.resolve(__dirname, 'node_modules')];
config.resolver.blockList = [
  ...Array.from(config.resolver.blockList ?? []),
  new RegExp(`^${escape(path.join(packageRoot, 'node_modules'))}/`),
];

function escape(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

module.exports = config;
