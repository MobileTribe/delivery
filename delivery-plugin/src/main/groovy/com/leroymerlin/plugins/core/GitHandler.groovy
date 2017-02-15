package com.leroymerlin.plugins.core

import com.leroymerlin.plugins.DeliveryPluginExtension
import com.leroymerlin.plugins.cli.Executor
import org.gradle.api.Project

/**
 * Created by alexandre on 06/02/2017.
 */
class GitHandler extends Executor implements BaseScmAdapter {
    Map params = ['directory': 'delivery-test', 'errorMessage': 'An error occured']

    @Override
    void setup(Project project, DeliveryPluginExtension extension) {
        println(exec(params, ['git', '--version']))
        //recuperation des credentials
        //Check dossier avec git
    }

    @Override
    void release() {
        //enlever les infos git
    }

    @Override
    String addAllFiles() throws ScmException {
        return println(exec(params, ['git', 'add', '.']))
    }

    @Override
    String commit(String comment) throws ScmException {
        return println(exec(params, ['git', 'commit', '-am', "\'" + comment + "\'"]))
    }

    @Override
    String deleteBranch(String branchName) throws ScmException {
        return println(exec(params, ['git', 'branch', '-d', branchName]))
    }

    @Override
    String switchBranch(String branchName, boolean createIfNeeded) throws ScmException {
        if (createIfNeeded)
            return println(exec(params, ['git', 'checkout', '-B', branchName]))
        else
            return println(exec(params, ['git', 'checkout', branchName]))
    }

    @Override
    String tag(String annotation, String message) throws ScmException {
        return println(exec(params, ['git', 'tag', '-a', annotation, '-m', '\'' + message + '\'']))
    }

    @Override
    String merge(String from) throws ScmException {
        return println(exec(params, ['git', 'merge', '--no-ff', from]))
    }

    @Override
    String push() throws ScmException {
        return println(exec(params, ['git', 'push']))
    }
}