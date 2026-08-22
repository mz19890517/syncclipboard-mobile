/**
 * FloatingClipboardPanelScreen
 * 悬浮球展开的剪贴板面板，承载于 QuickActionActivity 透明窗口。
 * 支持按类型筛选（全部/文本/图片/文件）与收藏视图，
 * 点击条目复制并关闭面板；底部提供打开主界面、上传、下载快捷操作。
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  Pressable,
  Alert,
  FlatList,
  useWindowDimensions,
  Platform,
  ToastAndroid,
} from 'react-native';
import { X } from 'react-native-feather';
import { useTranslation } from 'react-i18next';
import { HistoryListItem } from '@/components/HistoryListItem';
import { useTheme } from '@/hooks/useTheme';
import { useHistoryStore } from '@/stores/historyStore';
import type { HistoryItem } from '@/types/clipboard';
import type { HistoryFilter } from '@/types/storage';
import { historyStorage } from '@/storage';
import { localClipboard } from '@/services';
import {
  fetchRemoteClipboard,
  setLocalClipboardFromRemote,
  uploadLocalClipboard,
} from '@/services/sync/ClipboardSyncActions';
import { openMainApp } from 'floating-ball';

export type FloatingPanelType = 'all' | 'text' | 'image' | 'file' | 'fav';

interface FloatingClipboardPanelScreenProps {
  panelType?: FloatingPanelType;
  onComplete: () => void;
}

const PANEL_TABS: FloatingPanelType[] = ['all', 'text', 'image', 'file', 'fav'];

/** 面板 tab → 现有 i18n 键后缀（history.tabAll/Text/Image/File/Starred），as const 保证类型安全 */
const TAB_I18N_SUFFIX = {
  all: 'All',
  text: 'Text',
  image: 'Image',
  file: 'File',
  fav: 'Starred',
} as const;

function buildFilter(type: FloatingPanelType): HistoryFilter {
  switch (type) {
    case 'text':
      return { type: ['Text'] };
    case 'image':
      return { type: ['Image'] };
    case 'file':
      return { type: ['File', 'Group'] };
    case 'fav':
      return { starredOnly: true };
    default:
      return {};
  }
}

export function FloatingClipboardPanelScreen({
  panelType = 'all',
  onComplete,
}: FloatingClipboardPanelScreenProps) {
  const { t } = useTranslation();
  const { theme } = useTheme();
  const items = useHistoryStore((s) => s.items);
  const searchItems = useHistoryStore((s) => s.searchItems);
  const toggleStar = useHistoryStore((s) => s.toggleStar);
  const deleteItem = useHistoryStore((s) => s.deleteItem);

  /** 面板独立于主界面运行，store 级提示无挂载容器，直接用系统 Toast（与桌面快捷方式一致） */
  const notify = useCallback((msg: string) => {
    if (Platform.OS === 'android') ToastAndroid.show(msg, ToastAndroid.SHORT);
  }, []);

  const [activeTab, setActiveTab] = useState<FloatingPanelType>(panelType);
  const [busy, setBusy] = useState<'upload' | 'download' | null>(null);

  useEffect(() => {
    searchItems(buildFilter(activeTab))
      .then(() => {
        const count = useHistoryStore.getState().items.length;
        console.log(`[FloatingPanel] tab=${activeTab} search done, store items=${count}`);
      })
      .catch((e: unknown) => {
        console.error('[FloatingPanel] search failed:', e);
        notify(e instanceof Error ? e.message : String(e));
      });
  }, [activeTab, searchItems, notify]);

  const handleClose = useCallback(() => {
    onComplete();
  }, [onComplete]);

  const handleCopy = useCallback(
    async (item: HistoryItem) => {
      try {
        const content = {
          type: item.type,
          text: item.text,
          profileHash: item.profileHash,
          fileUri: item.fileUri,
          fileName: item.dataName,
          fileSize: item.size,
          timestamp: item.timestamp,
          localClipboardHash: item.localClipboardHash,
          hasData: item.hasData,
        };
        await localClipboard.setClipboardContent(content);
        historyStorage.updateLastAccessed(item.profileHash);
        notify(item.type === 'Image' ? t('clipboard.imageCopied') : t('clipboard.copied'));
        onComplete();
      } catch (error) {
        notify(error instanceof Error ? error.message : t('clipboard.copyFailed'));
      }
    },
    [notify, t, onComplete]
  );

  const handleLongPress = useCallback(
    (item: HistoryItem) => {
      Alert.alert(item.dataName || item.text.slice(0, 30), undefined, [
        {
          text: item.starred ? t('floatingPanel.unstar') : t('floatingPanel.star'),
          onPress: () => {
            toggleStar(item.profileHash);
            if (activeTab === 'fav') {
              searchItems(buildFilter('fav')).catch(() => {});
            }
          },
        },
        {
          text: t('common.delete'),
          style: 'destructive' as const,
          onPress: () => deleteItem(item.profileHash),
        },
        { text: t('common.cancel'), style: 'cancel' as const },
      ]);
    },
    [activeTab, deleteItem, searchItems, toggleStar, t]
  );

  const handleUpload = useCallback(async () => {
    if (busy) return;
    setBusy('upload');
    try {
      await uploadLocalClipboard();
      notify(t('quickTile.uploadSuccess'));
    } catch {
      notify(t('quickTile.uploadFailed'));
    } finally {
      setBusy(null);
    }
  }, [busy, notify, t]);

  const handleDownload = useCallback(async () => {
    if (busy) return;
    setBusy('download');
    try {
      const signal = new AbortController().signal;
      const content = await fetchRemoteClipboard(signal);
      await setLocalClipboardFromRemote(undefined, signal, content);
      notify(t('quickTile.syncSuccess'));
    } catch {
      notify(t('quickTile.syncFailed'));
    } finally {
      setBusy(null);
    }
  }, [busy, notify, t]);

  const handleOpenApp = useCallback(() => {
    openMainApp();
    onComplete();
  }, [onComplete]);

  const { height: windowHeight } = useWindowDimensions();
  // 保底高度：透明悬浮窗口里 Dimensions 可能返回 0 或异常值，导致整块列表不可见
  const listHeight = useMemo(() => Math.max(220, Math.round(windowHeight * 0.55)), [windowHeight]);

  const styles = useMemo(
    () =>
      StyleSheet.create({
        backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)', justifyContent: 'flex-end' },
        panel: {
          backgroundColor: theme.colors.surface ?? theme.colors.background,
          borderTopLeftRadius: 20,
          borderTopRightRadius: 20,
          paddingTop: 12,
          maxHeight: '88%',
        },
        header: {
          flexDirection: 'row',
          alignItems: 'center',
          paddingHorizontal: 16,
          marginBottom: 8,
        },
        title: { fontSize: 17, fontWeight: '600', color: theme.colors.text, flex: 1 },
        countBadge: {
          fontSize: 12,
          color: theme.colors.text,
          opacity: 0.6,
          marginRight: 8,
        },
        closeButton: { padding: 6 },
        tabs: { flexDirection: 'row', paddingHorizontal: 12, gap: 8, marginBottom: 8 },
        tab: {
          paddingHorizontal: 14,
          paddingVertical: 6,
          borderRadius: 999,
          backgroundColor: theme.colors.border ?? 'rgba(128,128,128,0.2)',
        },
        tabActive: { backgroundColor: theme.colors.primary },
        tabText: { fontSize: 13, color: theme.colors.text },
        tabTextActive: { color: '#FFFFFF', fontWeight: '600' },
        list: { paddingHorizontal: 8, flexGrow: 0 },
        listContainer: { flexGrow: 0 },
        empty: { textAlign: 'center', color: theme.colors.text, opacity: 0.5, paddingVertical: 40 },
        bottomBar: {
          flexDirection: 'row',
          borderTopWidth: StyleSheet.hairlineWidth,
          borderTopColor: theme.colors.border ?? 'rgba(128,128,128,0.3)',
          paddingTop: 10,
          paddingBottom: 24,
          paddingHorizontal: 8,
          gap: 8,
        },
        bottomButton: {
          flex: 1,
          alignItems: 'center',
          paddingVertical: 10,
          borderRadius: 12,
          backgroundColor: theme.colors.border ?? 'rgba(128,128,128,0.15)',
        },
        bottomButtonText: { fontSize: 13, color: theme.colors.text },
      }),
    [theme]
  );

  const renderBottomButton = (label: string, onPress: () => void, disabled = false) => (
    <Pressable
      key={label}
      style={({ pressed }) => [
        styles.bottomButton,
        disabled && { opacity: 0.5 },
        pressed && { opacity: 0.7 },
      ]}
      onPress={onPress}
      disabled={disabled}
    >
      <Text style={styles.bottomButtonText}>{label}</Text>
    </Pressable>
  );

  return (
    <Pressable style={styles.backdrop} onPress={handleClose}>
      <Pressable style={styles.panel} onPress={(e) => e.stopPropagation()}>
        <View style={styles.header}>
          <Text style={styles.title}>{t('floatingPanel.title')}</Text>
          <Text style={styles.countBadge}>{t('floatingPanel.count', { count: items.length })}</Text>
          <Pressable style={styles.closeButton} onPress={handleClose} hitSlop={8}>
            <X color={theme.colors.text} width={22} height={22} />
          </Pressable>
        </View>

        <View style={styles.tabs}>
          {PANEL_TABS.map((tab) => (
            <Pressable
              key={tab}
              style={[styles.tab, activeTab === tab && styles.tabActive]}
              onPress={() => setActiveTab(tab)}
            >
              <Text style={[styles.tabText, activeTab === tab && styles.tabTextActive]}>
                {t(`history.tab${TAB_I18N_SUFFIX[tab]}`)}
              </Text>
            </Pressable>
          ))}
        </View>

        <View style={{ height: listHeight }}>
          <FlatList
            data={items}
            keyExtractor={(item) => `${item.profileHash}_${item.timestamp}`}
            contentContainerStyle={styles.list}
            style={styles.listContainer}
            renderItem={({ item }) => (
              <HistoryListItem
                item={item}
                onCopy={handleCopy}
                onShare={async () => handleCopy(item)}
                onLongPress={handleLongPress}
                onToggleStar={async (starItem) => toggleStar(starItem.profileHash)}
                showImageCopyButton
              />
            )}
            ListEmptyComponent={<Text style={styles.empty}>{t('floatingPanel.empty')}</Text>}
          />
        </View>

        <View style={styles.bottomBar}>
          {renderBottomButton(t('floatingPanel.openApp'), handleOpenApp)}
          {renderBottomButton(t('floatingPanel.upload'), handleUpload, busy !== null)}
          {renderBottomButton(t('floatingPanel.download'), handleDownload, busy !== null)}
        </View>
      </Pressable>
    </Pressable>
  );
}
