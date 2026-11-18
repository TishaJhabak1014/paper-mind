import React from 'react';
import { Box, Button, CircularProgress, LinearProgress, Paper, Typography } from '@mui/material';

export default function GraphProgressNotice({ message, progress, error = false, actionLabel, onAction }) {
  const determinate = Number.isFinite(progress?.total) && progress.total > 0;
  const value = determinate ? Math.min(100, (progress.completed / progress.total) * 100) : undefined;

  return (
    <Paper
      role={error ? 'alert' : 'status'}
      aria-live="polite"
      variant="outlined"
      sx={{ p: 1.5, mb: 2, borderRadius: 2, bgcolor: 'background.paper' }}
    >
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.25 }}>
        {!error && <CircularProgress size={18} aria-hidden="true" />}
        <Typography variant="body2" color={error ? 'error' : 'text.secondary'}>
          {message}
        </Typography>
      </Box>
      {error && actionLabel && onAction && (
        <Button size="small" onClick={onAction} sx={{ mt: 0.5 }}>{actionLabel}</Button>
      )}
      {!error && (
        <LinearProgress
          variant={determinate ? 'determinate' : 'indeterminate'}
          value={value}
          sx={{ mt: 1.25, borderRadius: 1 }}
        />
      )}
    </Paper>
  );
}
