package com.fsck.k9.controller;


import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.fsck.k9.Account;
import com.fsck.k9.Account.Expunge;
import com.fsck.k9.K9;
import com.fsck.k9.mail.AuthenticationFailedException;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.Message;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.Store;
import com.fsck.k9.mail.store.imap.ImapFolder;
import com.fsck.k9.mail.store.imap.ImapMessage;
import com.fsck.k9.mail.store.imap.QresyncResponse;
import com.fsck.k9.mailstore.LocalFolder;
import com.fsck.k9.mailstore.LocalFolder.MoreMessages;
import com.fsck.k9.mailstore.LocalMessage;
import timber.log.Timber;


class ImapSyncInteractor {

    static void performSync(Account account, String folderName, MessagingListener listener,
            MessagingController controller, MessageDownloader messageDownloader) {

        Exception commandException = null;
        LocalFolder localFolder = null;
        ImapFolder imapFolder = null;

        try {
            Timber.d("SYNC: About to process pending commands for account %s", account.getDescription());

            try {
                controller.processPendingCommandsSynchronous(account);
            } catch (Exception e) {
                controller.addErrorMessage(account, null, e);
                Timber.e(e, "Failure processing command, but allow message sync attempt");
                commandException = e;
            }

            /*
             * Get the message list from the local store and create an index of
             * the uids within the list.
             */
            Timber.v("SYNC: About to get local folder %s", folderName);
            localFolder = SyncUtils.getOpenedLocalFolder(account, folderName);
            localFolder.updateLastUid();

            Store remoteStore = account.getRemoteStore();
            Timber.v("SYNC: About to get remote folder %s", folderName);
            Folder remoteFolder = remoteStore.getFolder(folderName);
            if (!(remoteFolder instanceof ImapFolder)) {
                throw new MessagingException("A non-IMAP account was provided");
            }
            imapFolder = (ImapFolder) remoteFolder;

            if (!SyncUtils.verifyOrCreateRemoteSpecialFolder(account, folderName, imapFolder, listener, controller)) {
                return;
            }

            /*
             * Open the remote folder. This pre-loads certain metadata like message count.
             */
            Timber.v("SYNC: About to open remote IMAP folder %s", folderName);
            QresyncResponse qresyncResponse;

            List<String> uids = localFolder.getAllMessageUids();
            if (uids.size() == 0) {
                qresyncResponse = imapFolder.open(Folder.OPEN_MODE_RW, localFolder.getUidValidity(),
                        localFolder.getHighestModSeq());
            } else {
                long smallestUid = Long.parseLong(uids.get(uids.size() - 1));
                qresyncResponse = imapFolder.open(Folder.OPEN_MODE_RW, localFolder.getUidValidity(),
                        localFolder.getHighestModSeq(), smallestUid);
            }

            if (Expunge.EXPUNGE_ON_POLL == account.getExpungePolicy()) {
                Timber.d("SYNC: Expunging folder %s:%s", account.getDescription(), folderName);
                imapFolder.expunge();
            }

            int remoteMessageCount = imapFolder.getMessageCount();
            if (remoteMessageCount < 0) {
                throw new IllegalStateException("Message count " + remoteMessageCount + " for folder " + folderName);
            } else {
                Timber.v("SYNC: Remote message count for folder %s is %d", folderName, remoteMessageCount);
            }

            handleUidValidity(account, listener, localFolder, imapFolder, controller);
            int newMessages;
            if (qresyncResponse == null) {
                newMessages = performSyncWithoutQresyncResult(account, localFolder, imapFolder, listener, controller,
                        messageDownloader);
            } else {
                newMessages = performSyncUsingQresyncResult(account, localFolder, imapFolder, listener, controller,
                        qresyncResponse, messageDownloader);
            }

            localFolder.setUidValidity(imapFolder.getUidValidity());
            updateHighestModSeqIfNecessary(localFolder, imapFolder);

            int unreadMessageCount = localFolder.getUnreadMessageCount();
            for (MessagingListener l : controller.getListeners()) {
                l.folderStatusChanged(account, folderName, unreadMessageCount);
            }

            /* Notify listeners that we're finally done. */
            localFolder.setLastChecked(System.currentTimeMillis());
            localFolder.setStatus(null);

            Timber.d("Done synchronizing folder %s:%s @ %tc with %d new messages",
                    account.getDescription(),
                    folderName,
                    System.currentTimeMillis(),
                    newMessages);

            for (MessagingListener l : controller.getListeners(listener)) {
                l.synchronizeMailboxFinished(account, folderName, imapFolder.getMessageCount(), newMessages);
            }

            if (commandException != null) {
                String rootMessage = MessagingController.getRootCauseMessage(commandException);
                Timber.e("Root cause failure in %s:%s was '%s'",
                        account.getDescription(), folderName, rootMessage);
                localFolder.setStatus(rootMessage);
                for (MessagingListener l : controller.getListeners(listener)) {
                    l.synchronizeMailboxFailed(account, folderName, rootMessage);
                }
            }

            Timber.i("Done synchronizing folder %s:%s", account.getDescription(), folderName);

        } catch (AuthenticationFailedException e) {
            controller.handleAuthenticationFailure(account, true);
            for (MessagingListener l : controller.getListeners(listener)) {
                l.synchronizeMailboxFailed(account, folderName, "Authentication failure");
            }
        } catch (Exception e) {
            Timber.e(e, "synchronizeMailbox");
            // If we don't set the last checked, it can try too often during
            // failure conditions
            String rootMessage = MessagingController.getRootCauseMessage(e);
            if (localFolder != null) {
                try {
                    localFolder.setStatus(rootMessage);
                    localFolder.setLastChecked(System.currentTimeMillis());
                } catch (MessagingException me) {
                    Timber.e(e, "Could not set last checked on folder %s:%s",
                            account.getDescription(), localFolder.getName());
                }
            }

            for (MessagingListener l : controller.getListeners(listener)) {
                l.synchronizeMailboxFailed(account, folderName, rootMessage);
            }
            controller.notifyUserIfCertificateProblem(account, e, true);
            controller.addErrorMessage(account, null, e);
            Timber.e("Failed synchronizing folder %s:%s @ %tc", account.getDescription(), folderName,
                    System.currentTimeMillis());

        } finally {
            MessagingController.closeFolder(imapFolder);
            MessagingController.closeFolder(localFolder);
        }
    }

    private static void handleUidValidity(Account account, MessagingListener listener, LocalFolder localFolder,
            ImapFolder imapFolder, MessagingController controller) throws MessagingException {
        long cachedUidValidity = localFolder.getUidValidity();
        long currentUidValidity = imapFolder.getUidValidity();

        if (cachedUidValidity != 0L && cachedUidValidity != currentUidValidity) {

            Timber.v("SYNC: Deleting all local messages in folder %s:%s due to UIDVALIDITY change", account, localFolder);
            Set<String> localUids = localFolder.getAllMessagesAndEffectiveDates().keySet();
            List<LocalMessage> destroyedMessages = localFolder.getMessagesByUids(localUids);

            localFolder.destroyMessages(localFolder.getMessagesByUids(localUids));
            for (Message destroyMessage : destroyedMessages) {
                for (MessagingListener l : controller.getListeners(listener)) {
                    l.synchronizeMailboxRemovedMessage(account, imapFolder.getName(), destroyMessage);
                }
            }
            localFolder.setHighestModSeq(0);
        }
    }

    private static int performSyncWithoutQresyncResult(Account account, LocalFolder localFolder,
            ImapFolder imapFolder, MessagingListener listener, MessagingController controller,
            MessageDownloader messageDownloader) throws MessagingException, IOException {
        Map<String, Long> localUidMap = localFolder.getAllMessagesAndEffectiveDates();
        String folderName = localFolder.getName();
        final List<ImapMessage> remoteMessages = new ArrayList<>();
        Map<String, ImapMessage> remoteUidMap = new HashMap<>();

        int remoteMessageCount = imapFolder.getMessageCount();
        final Date earliestDate = account.getEarliestPollDate();
        int remoteStart = getRemoteStart(localFolder, imapFolder);

        Timber.v("SYNC: About to get messages %d through %d for folder %s",
                remoteStart, remoteMessageCount, folderName);

        findRemoteMessagesToDownload(account, imapFolder, localUidMap, remoteMessages, remoteUidMap,
                remoteStart, listener, controller);

        /*
         * Remove any messages that are in the local store but no longer on the remote store or are too old
         */
        MoreMessages moreMessages = null;
        if (account.syncRemoteDeletions()) {
            moreMessages = syncRemoteDeletions(account, localFolder,
                    findDeletedMessageUidsWithoutQresync(localUidMap, remoteUidMap), listener, controller);
        }

        // noinspection UnusedAssignment, free memory early?
        localUidMap = null;

        if (moreMessages != null && moreMessages == MoreMessages.UNKNOWN) {
            updateMoreMessages(imapFolder, localFolder, earliestDate, remoteStart);
        }

        /*
         * Now we download the actual content of messages.
         */
        boolean useCondstore = false;
        long cachedHighestModSeq = localFolder.getHighestModSeq();
        if (cachedHighestModSeq != 0 && imapFolder.supportsModSeq()) {
            useCondstore = true;
        }
        int newMessages = messageDownloader.downloadMessages(account, imapFolder, localFolder, remoteMessages, false,
                true, !useCondstore);
        if (useCondstore) {
            Timber.v("SYNC: About to get messages having modseq greater that %d for IMAP folder %s",
                    cachedHighestModSeq, folderName);
            List<ImapMessage> syncFlagMessages = imapFolder.getChangedMessagesUsingCondstore(cachedHighestModSeq);
            newMessages += messageDownloader.downloadMessages(account, imapFolder, localFolder, syncFlagMessages,
                    false, true, true);
        }
        return newMessages;
    }

    private static int getRemoteStart(LocalFolder localFolder, ImapFolder imapFolder) throws MessagingException {
        int remoteMessageCount = imapFolder.getMessageCount();

        int visibleLimit = localFolder.getVisibleLimit();
        if (visibleLimit < 0) {
            visibleLimit = K9.DEFAULT_VISIBLE_LIMIT;
        }

        int remoteStart = 1;
        /* Message numbers start at 1.  */
        if (visibleLimit > 0) {
            remoteStart = Math.max(0, remoteMessageCount - visibleLimit) + 1;
        } else {
            remoteStart = 1;
        }
        return remoteStart;
    }

    private static int performSyncUsingQresyncResult(Account account, LocalFolder localFolder,
            ImapFolder imapFolder, MessagingListener listener, MessagingController controller,
            QresyncResponse qresyncResponse, MessageDownloader messageDownloader) throws MessagingException, IOException {
        String folderName = localFolder.getName();
        final List<ImapMessage> remoteMessages = new ArrayList<>();

        for (ImapMessage imapMessage : qresyncResponse.getModifiedMessages()) {
            Message localMessage = localFolder.getMessage(imapMessage.getUid());
            if (localMessage == null) {
                remoteMessages.add(imapMessage);
            } else {
                localMessage.setFlags(imapMessage.getFlags(), true);
                localFolder.appendMessages(Collections.singletonList(localMessage));
            }
        }

        /*
         * Remove any messages that are in the local store but no longer on the remote store or are too old
         */
        MoreMessages moreMessages = null;
        if (account.syncRemoteDeletions()) {
            moreMessages = syncRemoteDeletions(account, localFolder, qresyncResponse.getExpungedUids(),
                    listener, controller);
        }

        if (moreMessages != null && moreMessages == MoreMessages.UNKNOWN) {
            final Date earliestDate = account.getEarliestPollDate();
            int remoteStart = getRemoteStart(localFolder, imapFolder);
            updateMoreMessages(imapFolder, localFolder, earliestDate, remoteStart);
        }

        //TODO fetch historical messages till mailbox is filled to capacity and rewrite download mechanism
        return remoteMessages.size();
    }

    static void updateHighestModSeqIfNecessary(final LocalFolder localFolder, final Folder remoteFolder)
            throws MessagingException {
        if (remoteFolder instanceof ImapFolder) {
            ImapFolder imapFolder = (ImapFolder) remoteFolder;
            long cachedHighestModSeq = localFolder.getHighestModSeq();
            long remoteHighestModSeq = imapFolder.getHighestModSeq();
            if (remoteHighestModSeq > cachedHighestModSeq) {
                localFolder.setHighestModSeq(remoteHighestModSeq);
            }
        }
    }

    private static void findRemoteMessagesToDownload(Account account, ImapFolder imapFolder,
            Map<String, Long> localUidMap, List<ImapMessage> remoteMessages, Map<String, ImapMessage> remoteUidMap,
            int remoteStart, MessagingListener listener, MessagingController controller)
            throws MessagingException {

        String folderName = imapFolder.getName();
        int remoteMessageCount = imapFolder.getMessageCount();
        final Date earliestDate = account.getEarliestPollDate();
        long earliestTimestamp = earliestDate != null ? earliestDate.getTime() : 0L;
        final AtomicInteger headerProgress = new AtomicInteger(0);

        for (MessagingListener l : controller.getListeners(listener)) {
            l.synchronizeMailboxHeadersStarted(account, folderName);
        }

        List<ImapMessage> remoteMessageArray = imapFolder.getMessages(remoteStart, remoteMessageCount, earliestDate, null);

        int messageCount = remoteMessageArray.size();

        for (ImapMessage thisMess : remoteMessageArray) {
            headerProgress.incrementAndGet();
            for (MessagingListener l : controller.getListeners(listener)) {
                l.synchronizeMailboxHeadersProgress(account, folderName, headerProgress.get(), messageCount);
            }
            Long localMessageTimestamp = localUidMap.get(thisMess.getUid());
            if (localMessageTimestamp == null || localMessageTimestamp >= earliestTimestamp) {
                remoteMessages.add(thisMess);
                remoteUidMap.put(thisMess.getUid(), thisMess);
            }
        }

        Timber.v("SYNC: Got %d messages for folder %s", remoteUidMap.size(), folderName);

        for (MessagingListener l : controller.getListeners(listener)) {
            l.synchronizeMailboxHeadersFinished(account, folderName, headerProgress.get(), remoteUidMap.size());
        }
    }

    private static List<String> findDeletedMessageUidsWithoutQresync(Map<String, Long> localUidMap,
            Map<String, ImapMessage> remoteUidMap) {
        List<String> deletedMessageUids = new ArrayList<>();
        for (String localMessageUid : localUidMap.keySet()) {
            if (remoteUidMap.get(localMessageUid) == null) {
                deletedMessageUids.add(localMessageUid);
            }
        }
        return deletedMessageUids;
    }

    private static MoreMessages syncRemoteDeletions(Account account, LocalFolder localFolder,
            List<String> deletedMessageUids, MessagingListener listener, MessagingController controller)
            throws MessagingException {
        String folderName = localFolder.getName();
        MoreMessages moreMessages = localFolder.getMoreMessages();

        if (!deletedMessageUids.isEmpty()) {
            moreMessages = MoreMessages.UNKNOWN;
            List<LocalMessage> destroyMessages = localFolder.getMessagesByUids(deletedMessageUids);
            localFolder.destroyMessages(destroyMessages);

            for (Message destroyMessage : destroyMessages) {
                for (MessagingListener l : controller.getListeners(listener)) {
                    l.synchronizeMailboxRemovedMessage(account, folderName, destroyMessage);
                }
            }
        }
        return moreMessages;
    }

    private static void updateMoreMessages(ImapFolder remoteFolder, LocalFolder localFolder, Date earliestDate,
            int remoteStart) throws MessagingException, IOException {

        if (remoteStart == 1) {
            localFolder.setMoreMessages(MoreMessages.FALSE);
        } else {
            boolean moreMessagesAvailable = remoteFolder.areMoreMessagesAvailable(remoteStart, earliestDate);

            MoreMessages newMoreMessages = (moreMessagesAvailable) ? MoreMessages.TRUE : MoreMessages.FALSE;
            localFolder.setMoreMessages(newMoreMessages);
        }
    }
}